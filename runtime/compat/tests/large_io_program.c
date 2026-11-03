/* Execute production dynamic descriptor/FILE glue on AArch64. */
#include <kbl_port.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
int main(int argc,char **argv){
    (void)argc;(void)argv;int fd[80];FILE *stream[40];unsigned char byte;
    for(unsigned i=0;i<80;++i){fd[i]=open("/source",O_RDONLY);if(fd[i]<3)return 11;for(unsigned j=0;j<i;++j)if(fd[i]==fd[j])return 12;}
    for(unsigned i=0;i<40;++i){stream[i]=fopen("/source","r");if(!stream[i])return 13;for(unsigned j=0;j<i;++j)if(stream[i]==stream[j])return 14;}
    if(read(fd[79],&byte,1)!=1||byte!=0||fgetc(stream[39])!=0)return 15;
    if(close(fd[1]))return 16;fd[1]=open("/source",O_RDONLY);if(fd[1]!=4)return 17;
    if(fclose(stream[1]))return 18;stream[1]=fopen("/source","r");if(!stream[1]||fileno(stream[1])!=84)return 19;
    for(unsigned i=0;i<80;++i)if(close(fd[i]))return 20;
    for(unsigned i=0;i<40;++i)if(fclose(stream[i]))return 21;
    /* Program teardown closes every remaining dynamic record exactly once. */
    for(unsigned i=0;i<80;++i)if(open("/source",O_RDONLY)<3)return 22;
    for(unsigned i=0;i<40;++i)if(!fopen("/source","r"))return 23;
    return printf("dynamic descriptor and stream capacity passed\n")<0?24:0;
}
