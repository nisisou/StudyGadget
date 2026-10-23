#include "app_update.h"
#include "update_sha256.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int main(int argc,char **argv){
    if(argc<3)return 2;
    if(!strcmp(argv[1],"sha")){char digest[65];if(!sg_sha256_file(argv[2],digest))return 3;puts(digest);return 0;}
    if(!strcmp(argv[1],"validate")){if(argc!=5)return 4;printf("%d\n",app_update_validate(argv[2],(uint32_t)strtoul(argv[3],NULL,10),argv[4]));return 0;}
    if(!strcmp(argv[1],"install")){
        if(argc!=6)return 5;
        app_update_init(argv[2],true);
        char manifest[160];snprintf(manifest,sizeof manifest,"SGAPP 1 %s %s\n",argv[4],argv[5]);
        if(!app_update_offer(manifest)){printf("%d\n",app_update_state());return 0;}
        FILE *in=fopen(argv[3],"rb"),*out=fopen(app_update_staging(),"wb");if(!in||!out)return 6;
        char buffer[4096];size_t n;while((n=fread(buffer,1,sizeof buffer,in)))if(fwrite(buffer,1,n,out)!=n)return 7;
        fclose(in);fclose(out);app_update_downloading();app_update_install();
        printf("%d\n",app_update_state());return 0;
    }
    if(!strcmp(argv[1],"manifest")){app_update_init(argv[2],true);app_update_offer(argc>3?argv[3]:"");printf("%d\n",app_update_state());return 0;}
    return 8;
}
