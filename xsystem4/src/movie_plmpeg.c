/* Copyright (C) 2023 kichikuou <KichikuouChrome@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://gnu.org/licenses/>.
 */

#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <SDL.h>
#include <sndfile.h>

#include "system4.h"
#include "system4/file.h"

#include "apeg.h"
#include "gfx/gfx.h"
#include "movie.h"
#include "mixer.h"
#include "sprite.h"
#include "sts_mixer.h"
#include "xsystem4.h"

#define PL_MPEG_IMPLEMENTATION
#include "pl_mpeg.h"

#define APEG_AUDIO_CHUNK_FRAMES 1024

static Shader movie_shader;

struct movie_context {
	plm_t *plm;
	SDL_mutex *decoder_mutex;

	plm_frame_t *pending_video_frame;
	GLuint textures[3];  // Y, Cb, Cr

	// APEG state (NULL when playing MPEG-PS via pl_mpeg)
	struct apeg *apeg;
	int apeg_width, apeg_height;
	int apeg_duration;
	uint8_t *apeg_rgba;
	int last_pic_index;
	unsigned apeg_shown_handle;
	int apeg_time;
	bool apeg_size_warned;
	unsigned apeg_errors;
	bool playing;
	int start_ms;

	// APEG Ogg Vorbis audio via libsndfile
	uint8_t *sond_data;
	size_t sond_size;
	sf_count_t sond_offset;
	SNDFILE *sf;
	SF_INFO sf_info;
	float *sf_buf;
	sf_count_t sf_buf_frames;
	sf_count_t last_chunk_frames;
	SDL_mutex *audio_mutex;
	bool audio_finished;
	bool has_audio;

	// Fallback wall-clock for silent APEG (no SOND or audio init/play failed)
	uint64_t apeg_wall_start_ms;
	bool using_wall_clock;

	// Temporary surface for movie_draw(..., NULL) / mismatched sprites in APEG mode
	struct texture temp_surface;
	bool black_frame_drawn;

	sts_mixer_stream_t sts_stream;
	atomic_int voice;  // the mixer's stream handle
	int volume;
	int sound_group;  // movie_set_sound_group; -1: the master mixer

	// Time keeping data. Written by audio handler and referenced by video
	// handler (i.e. we sync the video to the audio).
	double stream_time;  // in seconds
	uint64_t wall_time_ms;
	SDL_mutex *timer_mutex;
};

static int audio_callback(sts_mixer_sample_t *sample, void *data)
{
	struct movie_context *mc = data;
	assert(sample == &mc->sts_stream.sample);

	SDL_LockMutex(mc->decoder_mutex);
	plm_samples_t *frame = plm_decode_audio(mc->plm);
	SDL_UnlockMutex(mc->decoder_mutex);
	if (!frame) {
		sample->length = 0;
		sample->data = NULL;
		atomic_store(&mc->voice, -1);
		return STS_STREAM_COMPLETE;
	}
	sample->length = frame->count * 2;
	sample->data = frame->interleaved;

	// Update the timestamp.
	SDL_LockMutex(mc->timer_mutex);
	mc->stream_time = frame->time;
	mc->wall_time_ms = SDL_GetTicks64();
	SDL_UnlockMutex(mc->timer_mutex);
	return STS_STREAM_CONTINUE;
}

static sf_count_t apeg_vio_get_filelen(void *data)
{
	struct movie_context *mc = data;
	return (sf_count_t)mc->sond_size;
}

static sf_count_t apeg_vio_seek(sf_count_t offset, int whence, void *data)
{
	struct movie_context *mc = data;
	sf_count_t pos = mc->sond_offset;
	switch (whence) {
	case SEEK_CUR:
		pos += offset;
		break;
	case SEEK_SET:
		pos = offset;
		break;
	case SEEK_END:
		pos = (sf_count_t)mc->sond_size + offset;
		break;
	default:
		return -1;
	}
	if (pos < 0)
		pos = 0;
	if (pos > (sf_count_t)mc->sond_size)
		pos = (sf_count_t)mc->sond_size;
	mc->sond_offset = pos;
	return pos;
}

static sf_count_t apeg_vio_read(void *ptr, sf_count_t count, void *data)
{
	struct movie_context *mc = data;
	if (count <= 0 || mc->sond_offset >= (sf_count_t)mc->sond_size)
		return 0;
	sf_count_t avail = (sf_count_t)mc->sond_size - mc->sond_offset;
	sf_count_t c = count < avail ? count : avail;
	memcpy(ptr, mc->sond_data + mc->sond_offset, (size_t)c);
	mc->sond_offset += c;
	return c;
}

static sf_count_t apeg_vio_write(possibly_unused const void *ptr,
				 possibly_unused sf_count_t count,
				 possibly_unused void *data)
{
	return -1;
}

static sf_count_t apeg_vio_tell(void *data)
{
	struct movie_context *mc = data;
	return mc->sond_offset;
}

static SF_VIRTUAL_IO apeg_vio = {
	.get_filelen = apeg_vio_get_filelen,
	.seek = apeg_vio_seek,
	.read = apeg_vio_read,
	.write = apeg_vio_write,
	.tell = apeg_vio_tell,
};

static int apeg_audio_callback(sts_mixer_sample_t *sample, void *data)
{
	struct movie_context *mc = data;
	assert(sample == &mc->sts_stream.sample);

	SDL_LockMutex(mc->audio_mutex);
	if (!mc->sf) {
		sample->length = 0;
		sample->data = NULL;
		mc->audio_finished = true;
		atomic_store(&mc->voice, -1);
		SDL_UnlockMutex(mc->audio_mutex);
		return STS_STREAM_COMPLETE;
	}

	sf_count_t frames = sf_readf_float(mc->sf, mc->sf_buf, mc->sf_buf_frames);
	if (frames <= 0) {
		sample->length = 0;
		sample->data = NULL;
		mc->audio_finished = true;
		SDL_LockMutex(mc->timer_mutex);
		if (mc->last_chunk_frames > 0 && mc->sf_info.samplerate > 0)
			mc->stream_time += (double)mc->last_chunk_frames / mc->sf_info.samplerate;
		mc->last_chunk_frames = 0;
		mc->wall_time_ms = SDL_GetTicks64();
		SDL_UnlockMutex(mc->timer_mutex);
		atomic_store(&mc->voice, -1);
		SDL_UnlockMutex(mc->audio_mutex);
		return STS_STREAM_COMPLETE;
	}

	if (mc->sf_info.channels == 1) {
		for (sf_count_t i = frames - 1; i >= 0; i--) {
			mc->sf_buf[i * 2 + 1] = mc->sf_buf[i];
			mc->sf_buf[i * 2]     = mc->sf_buf[i];
		}
	}

	sample->length = (unsigned int)(frames * 2);
	sample->data = mc->sf_buf;

	SDL_LockMutex(mc->timer_mutex);
	if (mc->last_chunk_frames > 0 && mc->sf_info.samplerate > 0)
		mc->stream_time += (double)mc->last_chunk_frames / mc->sf_info.samplerate;
	mc->last_chunk_frames = frames;
	mc->wall_time_ms = SDL_GetTicks64();
	SDL_UnlockMutex(mc->timer_mutex);

	SDL_UnlockMutex(mc->audio_mutex);
	return STS_STREAM_CONTINUE;
}

static bool apeg_init_context(struct movie_context *mc, const char *path)
{
	const char *err = NULL;
	mc->apeg = apeg_open(path, &err);
	if (!mc->apeg) {
		WARNING("%s: %s", path, err ? err : "apeg_open failed");
		return false;
	}
	if (apeg_warning(mc->apeg))
		WARNING("%s: %s", path, apeg_warning(mc->apeg));

	mc->apeg_width = apeg_width(mc->apeg);
	mc->apeg_height = apeg_height(mc->apeg);
	mc->apeg_duration = apeg_duration(mc->apeg);
	mc->last_pic_index = -1;
	mc->apeg_time = INT_MIN;

	if (mc->apeg_width > 0 && mc->apeg_height > 0)
		mc->apeg_rgba = xmalloc((size_t)mc->apeg_width * (size_t)mc->apeg_height * 4);

	mc->audio_mutex = SDL_CreateMutex();
	mc->timer_mutex = SDL_CreateMutex();

	uint64_t sound_offset = 0;
	uint32_t sound_size = 0;
	if (apeg_sound(mc->apeg, &sound_offset, &sound_size) && sound_size > 0) {
		FILE *sfp = file_open_utf8(path, "rb");
		if (sfp) {
#ifdef _WIN32
			bool seek_ok = sound_offset <= (uint64_t)LONG_MAX
				&& fseek(sfp, (long)sound_offset, SEEK_SET) == 0;
#else
			bool seek_ok = fseeko(sfp, (off_t)sound_offset, SEEK_SET) == 0;
#endif
			if (seek_ok) {
				mc->sond_data = malloc(sound_size);
				if (mc->sond_data && fread(mc->sond_data, 1, sound_size, sfp) == sound_size) {
					mc->sond_size = sound_size;
					mc->sond_offset = 0;
					mc->sf = sf_open_virtual(&apeg_vio, SFM_READ, &mc->sf_info, mc);
					if (!mc->sf || sf_error(mc->sf) != SF_ERR_NO_ERROR
					    || mc->sf_info.channels < 1 || mc->sf_info.channels > 2
					    || mc->sf_info.samplerate <= 0) {
						WARNING("%s: failed to open APEG audio (%s); falling back to silent playback",
							path, mc->sf ? sf_strerror(mc->sf) : "sf_open_virtual failed");
						if (mc->sf) {
							sf_close(mc->sf);
							mc->sf = NULL;
						}
						free(mc->sond_data);
						mc->sond_data = NULL;
						mc->sond_size = 0;
					} else {
						mc->sf_buf_frames = APEG_AUDIO_CHUNK_FRAMES;
						mc->sf_buf = xmalloc((size_t)mc->sf_buf_frames * 2 * sizeof(float));
						mc->has_audio = true;
					}
				} else {
					WARNING("%s: failed to read APEG SOND chunk; falling back to silent playback", path);
					free(mc->sond_data);
					mc->sond_data = NULL;
				}
			} else {
				WARNING("%s: failed to seek to APEG SOND chunk; falling back to silent playback", path);
			}
			fclose(sfp);
		} else {
			WARNING("%s: failed to reopen for APEG audio; falling back to silent playback", path);
		}
	}

	if (!mc->has_audio)
		mc->using_wall_clock = true;
	return true;
}

static void prepare_movie_shader(struct gfx_render_job *job, void *data)
{
	struct movie_context *mc = data;
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, mc->textures[0]);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, mc->textures[1]);
	glActiveTexture(GL_TEXTURE2);
	glBindTexture(GL_TEXTURE_2D, mc->textures[2]);
}

static void load_movie_shader()
{
	gfx_load_shader(&movie_shader, "shaders/render.v.glsl", "shaders/movie.f.glsl");
	glUseProgram(movie_shader.program);
	glUniform1i(glGetUniformLocation(movie_shader.program, "texture_y"), 0);
	glUniform1i(glGetUniformLocation(movie_shader.program, "texture_cb"), 1);
	glUniform1i(glGetUniformLocation(movie_shader.program, "texture_cr"), 2);
	movie_shader.prepare = prepare_movie_shader;
}

static void update_texture(GLuint unit, GLuint texture, plm_plane_t *plane)
{
	glActiveTexture(unit);
	glBindTexture(GL_TEXTURE_2D, texture);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, plane->width, plane->height, 0,
	             GL_RED, GL_UNSIGNED_BYTE, plane->data);
}

struct movie_context *movie_load(const char *filename)
{
	struct movie_context *mc = xcalloc(1, sizeof(struct movie_context));
	atomic_init(&mc->voice, -1);
	mc->sound_group = -1;
	mc->last_pic_index = -1;
	mc->volume = 100;

	char *path = gamedir_path_icase(filename);
	if (!path) {
		WARNING("%s: file does not exist", filename);
		movie_free(mc);
		return NULL;
	}
	FILE *fp = file_open_utf8(path, "rb");
	if (!fp) {
		WARNING("%s: %s", path, strerror(errno));
		free(path);
		movie_free(mc);
		return NULL;
	}

	uint8_t magic[4];
	if (fread(magic, 1, 4, fp) == 4 && memcmp(magic, "APEG", 4) == 0) {
		fclose(fp);
		bool ok = apeg_init_context(mc, path);
		free(path);
		if (!ok) {
			movie_free(mc);
			return NULL;
		}
		return mc;
	}

	if (fseek(fp, 0, SEEK_SET) != 0) {
		WARNING("%s: %s", path, strerror(errno));
		fclose(fp);
		free(path);
		movie_free(mc);
		return NULL;
	}
	mc->plm = plm_create_with_file(fp, TRUE);
	if (!mc->plm || !plm_has_headers(mc->plm)) {
		WARNING("%s: not a MPEG-PS file", path);
		free(path);
		movie_free(mc);
		return NULL;
	}
	free(path);

	if (!movie_shader.program)
		load_movie_shader();

	glGenTextures(3, mc->textures);
	for (int i = 0; i < 3; i++) {
		glBindTexture(GL_TEXTURE_2D, mc->textures[i]);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	}

	mc->decoder_mutex = SDL_CreateMutex();
	mc->timer_mutex = SDL_CreateMutex();
	return mc;
}

void movie_free(struct movie_context *mc)
{
	if (!mc)
		return;
	int voice = atomic_load(&mc->voice);
	if (voice >= 0)
		mixer_stream_stop(voice);
	mixer_movie_sound_release(mc);

	// Wait for any in-flight audio callback returning STS_STREAM_COMPLETE to exit.
	if (mc->audio_mutex) {
		SDL_LockMutex(mc->audio_mutex);
		SDL_UnlockMutex(mc->audio_mutex);
	}

	if (mc->sf)
		sf_close(mc->sf);
	free(mc->sf_buf);
	free(mc->sond_data);
	apeg_close(mc->apeg);
	free(mc->apeg_rgba);
	if (mc->temp_surface.handle)
		gfx_delete_texture(&mc->temp_surface);

	if (mc->plm)
		plm_destroy(mc->plm);
	if (mc->textures[0])
		glDeleteTextures(3, mc->textures);
	if (mc->audio_mutex)
		SDL_DestroyMutex(mc->audio_mutex);
	if (mc->decoder_mutex)
		SDL_DestroyMutex(mc->decoder_mutex);
	if (mc->timer_mutex)
		SDL_DestroyMutex(mc->timer_mutex);
	free(mc);
}

bool movie_play(struct movie_context *mc)
{
	if (mc->apeg) {
		mc->apeg_time = INT_MIN;
		mc->playing = true;
		if (mc->has_audio && mc->sf) {
			SDL_LockMutex(mc->timer_mutex);
			mc->stream_time = mc->start_ms / 1000.0;
			mc->wall_time_ms = SDL_GetTicks64();
			SDL_UnlockMutex(mc->timer_mutex);

			mc->sts_stream.userdata = mc;
			mc->sts_stream.callback = apeg_audio_callback;
			mc->sts_stream.sample.frequency = (unsigned int)mc->sf_info.samplerate;
			mc->sts_stream.sample.audio_format = STS_MIXER_SAMPLE_FORMAT_FLOAT;
			mc->sts_stream.sample.length = 0;
			mc->sts_stream.sample.data = NULL;

			int voice = mixer_stream_play_group(&mc->sts_stream, mc->volume, mc->sound_group);
			if (voice >= 0) {
				atomic_store(&mc->voice, voice);
				mc->using_wall_clock = false;
				return true;
			}
			WARNING("APEG: mixer_stream_play_group failed; falling back to wall clock");
			mc->has_audio = false;
		}
		mc->using_wall_clock = true;
		mc->apeg_wall_start_ms = SDL_GetTicks64();
		return true;
	}

	// Start the audio stream.
	mc->stream_time = 0.0;
	mc->wall_time_ms = SDL_GetTicks64();
	mc->sts_stream.userdata = mc;
	mc->sts_stream.callback = audio_callback;
	mc->sts_stream.sample.frequency = plm_get_samplerate(mc->plm);
	mc->sts_stream.sample.audio_format = STS_MIXER_SAMPLE_FORMAT_FLOAT;
	atomic_store(&mc->voice, mixer_stream_play_group(&mc->sts_stream, mc->volume, mc->sound_group));
	return true;
}

void movie_set_sound_group(struct movie_context *mc, int sound_id, int sound_group)
{
	mc->sound_group = mixer_movie_sound_set(sound_id, sound_group, mc);
}

static bool apeg_update_texture(struct movie_context *mc, struct texture *tex)
{
	if (tex->w != mc->apeg_width || tex->h != mc->apeg_height) {
		if (!mc->apeg_size_warned)
			WARNING("APEG %dx%d cannot be drawn into a %dx%d texture",
				mc->apeg_width, mc->apeg_height, tex->w, tex->h);
		mc->apeg_size_warned = true;
		return false;
	}
	int t = movie_get_position(mc);
	if (t < mc->apeg_time)
		t = mc->apeg_time;
	mc->apeg_time = t;
	const struct apeg_picture *p = apeg_picture_at(mc->apeg, t);
	if (!p) {
		const char *err = apeg_error(mc->apeg);
		if (err) {
			mc->apeg_errors++;
			if (mc->apeg_errors <= 8 || !(mc->apeg_errors & 255))
				WARNING("APEG picture at %d ms: %s (%u failures so far)", t, err, mc->apeg_errors);
		}
		return false;
	}
	if (p->index == mc->last_pic_index && tex->handle == mc->apeg_shown_handle)
		return false;
	if (!mc->apeg_rgba)
		mc->apeg_rgba = xmalloc((size_t)p->width * (size_t)p->height * 4);
	apeg_picture_to_rgba(mc->apeg, p, mc->apeg_rgba, (size_t)p->width * 4);
	gfx_update_texture_with_pixels(tex, mc->apeg_rgba);
	mc->last_pic_index = p->index;
	mc->apeg_shown_handle = tex->handle;
	return true;
}

static bool draw_black_once(struct movie_context *mc, struct sact_sprite *sprite)
{
	if (!mc->black_frame_drawn) {
		mc->black_frame_drawn = true;
		struct texture *tex = sprite ? sprite_get_texture(sprite) : NULL;
		if (tex && tex->w > 0 && tex->h > 0) {
			size_t npixels = (size_t)tex->w * (size_t)tex->h;
			uint32_t *black = xcalloc(npixels, sizeof(uint32_t));
			gfx_update_texture_with_pixels(tex, black);
			free(black);
			if (sprite)
				sprite_dirty(sprite);
		}
	}
	return !movie_is_end(mc);
}

bool movie_draw(struct movie_context *mc, struct sact_sprite *sprite)
{
	if (mc->apeg) {
		if (sprite) {
			struct texture *tex = sprite_get_texture(sprite);
			if (tex->w != mc->apeg_width || tex->h != mc->apeg_height) {
				if (!mc->apeg_size_warned)
					WARNING("APEG %dx%d cannot be drawn into a %dx%d sprite; drawing it black",
						mc->apeg_width, mc->apeg_height, tex->w, tex->h);
				mc->apeg_size_warned = true;
				return draw_black_once(mc, sprite);
			}
			if (movie_update_texture(mc, tex))
				sprite_dirty(sprite);
			else if (mc->last_pic_index < 0)
				draw_black_once(mc, sprite);
		} else {
			if (!mc->temp_surface.handle && mc->apeg_width > 0 && mc->apeg_height > 0)
				gfx_init_texture_blank(&mc->temp_surface, mc->apeg_width, mc->apeg_height);
			if (mc->temp_surface.handle && movie_update_texture(mc, &mc->temp_surface)) {
				gfx_clear();
				gfx_render_texture(&mc->temp_surface, NULL);
				gfx_swap();
			}
		}
		return !movie_is_end(mc);
	}

	// Decode a frame, unless we already have one.
	plm_frame_t *frame = mc->pending_video_frame;
	mc->pending_video_frame = NULL;
	if (!frame) {
		SDL_LockMutex(mc->decoder_mutex);
		frame = plm_decode_video(mc->plm);
		SDL_UnlockMutex(mc->decoder_mutex);
		if (!frame)
			return plm_video_has_ended(mc->plm->video_decoder);
	}

	// If the frame's timestamp is in the future, save the frame and return.
	SDL_LockMutex(mc->timer_mutex);
	double now = mc->stream_time + (SDL_GetTicks64() - mc->wall_time_ms) / 1000.0;
	SDL_UnlockMutex(mc->timer_mutex);
	if (frame->time > now) {
		mc->pending_video_frame = frame;
		return true;
	}

	// Render the frame.
	update_texture(GL_TEXTURE0, mc->textures[0], &frame->y);
	update_texture(GL_TEXTURE1, mc->textures[1], &frame->cb);
	update_texture(GL_TEXTURE2, mc->textures[2], &frame->cr);

	float w, h;
	GLuint fbo;
	if (sprite) {
		struct texture *texture = sprite_get_texture(sprite);
		w = texture->w;
		h = texture->h;
		fbo = gfx_set_framebuffer(GL_DRAW_FRAMEBUFFER, texture, 0, 0, w, h);
	} else {
		// Draw directly to the main framebuffer.
		w = config.view_width;
		h = config.view_height;
		gfx_clear();
	}

	mat4 world_transform = WORLD_TRANSFORM(w, h, 0, 0);
	mat4 wv_transform = WV_TRANSFORM(w, h);

	struct gfx_render_job job = {
		.shader = &movie_shader,
		.shape = GFX_RECTANGLE,
		.texture = 0,
		.world_transform = world_transform[0],
		.view_transform = wv_transform[0],
		.data = mc
	};
	gfx_render(&job);

	if (sprite) {
		gfx_reset_framebuffer(GL_DRAW_FRAMEBUFFER, fbo);
		sprite_dirty(sprite);
	} else {
		gfx_swap();
	}

	return true;
}

bool movie_is_end(struct movie_context *mc)
{
	if (mc->apeg) {
		if (!mc->playing)
			return false;
		int pos = movie_get_position(mc);
		if (mc->has_audio && !mc->using_wall_clock)
			return mc->audio_finished && pos >= mc->apeg_duration;
		return pos >= mc->apeg_duration;
	}
	return plm_has_ended(mc->plm);
}

int movie_get_position(struct movie_context *mc)
{
	if (mc->apeg) {
		if (!mc->playing)
			return mc->start_ms;
		if (mc->using_wall_clock || !mc->has_audio) {
			uint64_t now = SDL_GetTicks64();
			int ms = mc->start_ms + (int)(now - mc->apeg_wall_start_ms);
			if (mc->apeg_duration > 0 && ms > mc->apeg_duration)
				ms = mc->apeg_duration;
			return ms;
		}
		SDL_LockMutex(mc->timer_mutex);
		int ms = mc->wall_time_ms
			? (int)(mc->stream_time * 1000.0 + (double)(SDL_GetTicks64() - mc->wall_time_ms))
			: mc->start_ms;
		SDL_UnlockMutex(mc->timer_mutex);
		if (mc->audio_finished && mc->apeg_duration > 0 && ms < mc->apeg_duration)
			ms = mc->apeg_duration;
		return ms;
	}
	SDL_LockMutex(mc->timer_mutex);
	int ms = mc->wall_time_ms ? (int)(mc->stream_time * 1000.0 + (double)(SDL_GetTicks64() - mc->wall_time_ms)) : 0;
	SDL_UnlockMutex(mc->timer_mutex);
	return ms;
}

bool movie_set_volume(struct movie_context *mc, int volume)
{
	mc->volume = volume;
	int voice = atomic_load(&mc->voice);
	if (voice >= 0)
		mixer_stream_set_volume(voice, volume);
	return true;
}

void movie_get_video_size(struct movie_context *mc, int *out_w, int *out_h)
{
	if (mc->apeg) {
		if (out_w) *out_w = mc->apeg_width;
		if (out_h) *out_h = mc->apeg_height;
		return;
	}
	if (out_w) *out_w = plm_get_width(mc->plm);
	if (out_h) *out_h = plm_get_height(mc->plm);
}

int movie_get_duration(struct movie_context *mc)
{
	if (mc->apeg)
		return mc->apeg_duration;
	return (int)(plm_get_duration(mc->plm) * 1000);
}

bool movie_seek(struct movie_context *mc, int msec)
{
	if (!mc->apeg)
		return msec == 0;
	if (msec < 0)
		return false;

	mc->last_pic_index = -1;
	mc->apeg_time = INT_MIN;

	if (msec == 0)
		apeg_rewind(mc->apeg);

	if (mc->has_audio && mc->sf && mc->sf_info.samplerate > 0) {
		sf_count_t target_frame = (sf_count_t)msec * mc->sf_info.samplerate / 1000;
		if (mc->sf_info.frames > 0 && target_frame > mc->sf_info.frames)
			target_frame = mc->sf_info.frames;

		SDL_LockMutex(mc->audio_mutex);
		sf_count_t actual = sf_seek(mc->sf, target_frame, SEEK_SET);
		if (actual < 0) {
			WARNING("movie_seek(%d) failed; playing from the start", msec);
			sf_seek(mc->sf, 0, SEEK_SET);
			mc->audio_finished = false;
			mc->last_chunk_frames = 0;
			SDL_UnlockMutex(mc->audio_mutex);

			mc->start_ms = 0;
			SDL_LockMutex(mc->timer_mutex);
			mc->stream_time = 0.0;
			mc->wall_time_ms = mc->playing ? SDL_GetTicks64() : 0;
			SDL_UnlockMutex(mc->timer_mutex);
			return false;
		}
		mc->audio_finished = (mc->sf_info.frames > 0 && actual >= mc->sf_info.frames);
		mc->last_chunk_frames = 0;
		SDL_UnlockMutex(mc->audio_mutex);

		double actual_sec = (double)actual / mc->sf_info.samplerate;
		mc->start_ms = (int)(actual_sec * 1000.0);
		SDL_LockMutex(mc->timer_mutex);
		mc->stream_time = actual_sec;
		mc->wall_time_ms = mc->playing ? SDL_GetTicks64() : 0;
		SDL_UnlockMutex(mc->timer_mutex);
		return true;
	}

	mc->start_ms = msec;
	mc->apeg_wall_start_ms = mc->playing ? SDL_GetTicks64() : 0;
	return true;
}

bool movie_update_texture(struct movie_context *mc, struct texture *tex)
{
	if (!mc->apeg || !mc->playing || !tex || !tex->handle)
		return false;
	return apeg_update_texture(mc, tex);
}
