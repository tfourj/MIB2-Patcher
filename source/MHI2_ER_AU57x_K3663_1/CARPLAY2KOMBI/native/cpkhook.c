/*
 * CARPLAY2KOMBI hook, preloaded into dio_manager with LD_PRELOAD.
 *
 * libairplay.so hands every compressed CarPlay video buffer to NvSSVideoDecode
 * in libnvss_video.so. This library sits in front of it, passes every call on
 * unchanged and, while /ramdisk/cpk/dump exists, copies the H.264 to
 * /ramdisk/cpk/video.h264 with one line per buffer in /ramdisk/cpk/index.txt.
 *
 * Built without QNX headers: the few libc functions are declared here and
 * resolved against the unit's libc.so.3 at link time (see build.sh).
 */

typedef unsigned char u8;
typedef unsigned int u32;
typedef unsigned long long u64;
typedef struct FILE FILE;

extern void *dlopen(const char *name, int mode);
extern void *dlsym(void *handle, const char *name);
extern char *dlerror(void);
extern u32 strlen(const char *text);
extern char *getenv(const char *name);
extern int access(const char *path, int mode);
extern FILE *fopen(const char *path, const char *mode);
extern u32 fwrite(const void *data, u32 size, u32 count, FILE *file);
extern int fflush(FILE *file);
extern int fclose(FILE *file);

/*
 * RTLD_NEXT (-3 in libc.so.3) finds nothing from a preloaded library, so the real
 * functions come from dlopen of the already loaded libnvss_video.so instead.
 * The mode is the one the firmware's mm-ipod passes to dlopen
 */
#define NVSS_LIBRARY "libnvss_video.so"
#define DLOPEN_MODE 0x100

#define DUMP_TRIGGER "/ramdisk/cpk/dump"
#define DUMP_VIDEO "/ramdisk/cpk/video.h264"
#define DUMP_INDEX "/ramdisk/cpk/index.txt"
#define SELFTEST_FILE "/ramdisk/cpk/selftest.txt"
/* /ramdisk is 20 MB */
#define DUMP_MAX_BYTES (8u * 1024u * 1024u)

/* Second argument of NvSSVideoDecode, from its memcpy(dst, [r1], [r1 + 4]) */
struct nvss_buffer {
	const u8 *data;
	u32 len;
	u64 pts;
	u8 flag;
};

typedef int (*decode_fn)(void *handle, struct nvss_buffer *buffer, u32 arg);
typedef int (*configure_fn)(void *handle, const u8 *config);

static decode_fn real_decode;
static configure_fn real_configure;

static FILE *video_file;
static FILE *index_file;
static u32 dumped_bytes;
static u32 frame_count;

static void resolve(void)
{
	static void *nvss;

	if (real_decode && real_configure)
		return;
	if (!nvss)
		nvss = dlopen(NVSS_LIBRARY, DLOPEN_MODE);
	if (!nvss)
		return;
	if (!real_decode)
		real_decode = (decode_fn)dlsym(nvss, "NvSSVideoDecode");
	if (!real_configure)
		real_configure = (configure_fn)dlsym(nvss, "NvSSVideoStreamConfigure");
}

/* Appends value as hex digits to out, returns the new end */
static char *put_hex(char *out, u64 value, int digits)
{
	static const char hex[] = "0123456789abcdef";
	int i;

	for (i = digits - 1; i >= 0; i--)
		*out++ = hex[(value >> (i * 4)) & 0xf];
	return out;
}

static void write_line(FILE *file, const char *start, char *end)
{
	*end++ = '\n';
	fwrite(start, 1, (u32)(end - start), file);
}

static void stop_dump(void)
{
	if (video_file)
		fclose(video_file);
	if (index_file)
		fclose(index_file);
	video_file = 0;
	index_file = 0;
}

/* True while dumping is wanted and possible. Opens the files on the first frame */
static int dump_enabled(void)
{
	if (access(DUMP_TRIGGER, 0) != 0 || dumped_bytes >= DUMP_MAX_BYTES) {
		stop_dump();
		return 0;
	}
	if (!video_file) {
		video_file = fopen(DUMP_VIDEO, "ab");
		index_file = fopen(DUMP_INDEX, "a");
		if (!video_file || !index_file) {
			stop_dump();
			return 0;
		}
	}
	return 1;
}

static void dump_buffer(const struct nvss_buffer *buffer, u32 arg)
{
	char line[96];
	char *p = line;
	u32 len = buffer->len;

	if (dumped_bytes + len > DUMP_MAX_BYTES)
		len = DUMP_MAX_BYTES - dumped_bytes;
	fwrite(buffer->data, 1, len, video_file);
	dumped_bytes += len;

	/* frame len pts flag arg first-bytes */
	p = put_hex(p, frame_count, 6);
	*p++ = ' ';
	p = put_hex(p, buffer->len, 8);
	*p++ = ' ';
	p = put_hex(p, buffer->pts, 16);
	*p++ = ' ';
	p = put_hex(p, buffer->flag, 2);
	*p++ = ' ';
	p = put_hex(p, arg, 8);
	*p++ = ' ';
	p = put_hex(p, buffer->len >= 8 ? ((u64)buffer->data[0] << 56 | (u64)buffer->data[1] << 48 |
		(u64)buffer->data[2] << 40 | (u64)buffer->data[3] << 32 | (u64)buffer->data[4] << 24 |
		(u64)buffer->data[5] << 16 | (u64)buffer->data[6] << 8 | buffer->data[7]) : 0, 16);
	write_line(index_file, line, p);
	fflush(video_file);
	fflush(index_file);
}

int NvSSVideoDecode(void *handle, struct nvss_buffer *buffer, u32 arg)
{
	resolve();
	if (buffer && buffer->data && buffer->len && dump_enabled())
		dump_buffer(buffer, arg);
	frame_count++;
	if (!real_decode)
		return (int)0x80000000;
	return real_decode(handle, buffer, arg);
}

int NvSSVideoStreamConfigure(void *handle, const u8 *config)
{
	char line[96];
	char *p = line;
	int i;

	resolve();
	if (config && dump_enabled()) {
		p = put_hex(p, 0xc0f16, 6);
		for (i = 0; i < 32; i++) {
			*p++ = ' ';
			p = put_hex(p, config[i], 2);
		}
		write_line(index_file, line, p);
		fflush(index_file);
	}
	if (!real_configure)
		return (int)0x80000000;
	return real_configure(handle, config);
}

/*
 * CPK_SELFTEST=1: for a dry run outside CarPlay, write the real functions found,
 * our own NvSSVideoDecode (must differ) and dlerror()
 */
__attribute__((constructor)) static void selftest(void)
{
	char line[64];
	char *p = line;
	const char *error;
	FILE *file;

	if (!getenv("CPK_SELFTEST"))
		return;
	resolve();
	error = dlerror();
	file = fopen(SELFTEST_FILE, "w");
	if (!file)
		return;
	p = put_hex(p, (u32)real_decode, 8);
	*p++ = ' ';
	p = put_hex(p, (u32)real_configure, 8);
	*p++ = ' ';
	p = put_hex(p, (u32)NvSSVideoDecode, 8);
	write_line(file, line, p);
	if (error)
		fwrite(error, 1, strlen(error), file);
	fclose(file);
}
