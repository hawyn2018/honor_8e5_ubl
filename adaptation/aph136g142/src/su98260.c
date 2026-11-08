#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define ROOT_SOCK "/data/local/tmp/pc98260-root.sock"

static int write_all(int fd, const void *buffer, size_t length)
{
	const char *p = buffer;
	while (length) {
		ssize_t sent = write(fd, p, length);
		if (sent < 0 && errno == EINTR) continue;
		if (sent <= 0) return -1;
		p += sent;
		length -= (size_t)sent;
	}
	return 0;
}

int main(int argc, char **argv)
{
	const char *path = getenv("PC98260_SOCK");
	if (!path || !*path) path = ROOT_SOCK;
	const char *command = "";
	if (argc == 3 && !strcmp(argv[1], "-c")) command = argv[2];
	else if (argc != 1) {
		fprintf(stderr, "usage: %s [-c command]\n", argv[0]);
		return 2;
	}
	size_t length = strlen(command);
	if (length > 4096U) return 2;
	int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (fd < 0) return 1;
	struct sockaddr_un address = { .sun_family = AF_UNIX };
	if (strlen(path) >= sizeof(address.sun_path)) {
		close(fd);
		return 1;
	}
	strncpy(address.sun_path, path, sizeof(address.sun_path) - 1);
	if (connect(fd, (struct sockaddr *)&address, sizeof(address))) {
		perror("connect root daemon");
		close(fd);
		return 1;
	}
	uint32_t command_length = (uint32_t)length;
	if (write_all(fd, &command_length, sizeof(command_length)) ||
	    write_all(fd, command, length)) {
		close(fd);
		return 1;
	}
	if (length) (void)shutdown(fd, SHUT_WR);
	bool input_open = !length;
	for (;;) {
		struct pollfd fds[2] = {
			{ .fd = fd, .events = POLLIN },
			{ .fd = STDIN_FILENO, .events = input_open ? POLLIN : 0 }
		};
		int ready = poll(fds, input_open ? 2 : 1, -1);
		if (ready < 0 && errno == EINTR) continue;
		if (ready < 0) break;
		if (fds[0].revents & (POLLIN | POLLHUP)) {
			char buffer[4096];
			ssize_t got = read(fd, buffer, sizeof(buffer));
			if (got <= 0) break;
			if (write_all(STDOUT_FILENO, buffer, (size_t)got)) break;
		}
		if (input_open && (fds[1].revents & (POLLIN | POLLHUP))) {
			char buffer[4096];
			ssize_t got = read(STDIN_FILENO, buffer, sizeof(buffer));
			if (got <= 0) {
				input_open = false;
				(void)shutdown(fd, SHUT_WR);
			} else if (write_all(fd, buffer, (size_t)got)) break;
		}
	}
	close(fd);
	return 0;
}
