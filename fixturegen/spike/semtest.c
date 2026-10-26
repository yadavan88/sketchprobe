#include <semaphore.h>
#include <fcntl.h>
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
int main(void) {
  char name[64]; snprintf(name, sizeof name, "/sptest-%d", getpid());
  sem_t *s = sem_open(name, O_CREAT | O_EXCL, 0600, 0);
  if (s == SEM_FAILED) printf("sem_open(named POSIX semaphore): FAILED errno=%d (%s)\n", errno, strerror(errno));
  else { printf("sem_open(named POSIX semaphore): ok\n"); sem_close(s); sem_unlink(name); }
  int sv[2];
  if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0) printf("socketpair: ok\n"); else printf("socketpair: FAILED errno=%d (%s)\n", errno, strerror(errno));
  int p[2];
  if (pipe(p) == 0) printf("pipe: ok\n"); else printf("pipe: FAILED errno=%d (%s)\n", errno, strerror(errno));
  return 0;
}
