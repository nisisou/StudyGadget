#include "character.h"
#include <3ds.h>
#include <citro3d.h>
#include <citro2d.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "character_shader.h"

#define MAXB 96
#define MAXV 24000
#define MAXM 96
typedef struct { char name[32]; int32_t parent; float local[16], inverse[16]; } Bone;
typedef struct { float p[3],uv[2],color[4]; uint8_t bone[4]; float weight[4]; } Vertex;
typedef struct { float p[3],uv[2],color[4]; } GPUVertex;
typedef struct { uint32_t start,count,cull; } Batch;
typedef struct { uint32_t version,skeleton,bones,vertices,texture,batches,crc,bytes; } Header;
typedef struct { int state; float duration,speed,intensity,sway,head,arm,phase; } Motion;
typedef struct { Header h; Bone *bones; Vertex *vertices; Batch *batch; GPUVertex *gpu[2]; C3D_Tex tex; } Model;
static Model model;
static Motion motions[MAXM]; static unsigned nm,revision;
static DVLB_s *dvlb; static shaderProgram_s program; static int projection_loc;
static C3D_Mtx projection;
static bool shader_ready;
static unsigned buffer_idx;
static char status[80]="No character: copy model.sgch to SD";
static float pose[MAXB][3], from_pose[MAXB][3], world[MAXB][16], rest[MAXB][16], skin[MAXB][16];
static int last_state=-1, selected=-1; static float changed,until;
static uint32_t rng=0x731aad;
static uint32_t random32(void){ rng^=rng<<13; rng^=rng>>17; rng^=rng<<5;return rng; }
static uint32_t crc32(const unsigned char *p,size_t n){uint32_t c=~0u;while(n--){c^=*p++;for(int i=0;i<8;i++)c=(c>>1)^((0u-(c&1))&0xedb88320);}return ~c;}
static void ident(float *m){memset(m,0,64);m[0]=m[5]=m[10]=m[15]=1;}
static void mul(float *o,const float *a,const float *b){float t[16];for(int i=0;i<4;i++)for(int j=0;j<4;j++){t[i*4+j]=0;for(int k=0;k<4;k++)t[i*4+j]+=a[i*4+k]*b[k*4+j];}memcpy(o,t,64);}
static bool finite_array(const float *v,int n){for(int i=0;i<n;i++)if(!isfinite(v[i])||fabsf(v[i])>10000)return false;return true;}
static void release(Model *m){free(m->bones);free(m->vertices);free(m->batch);if(m->gpu[0])linearFree(m->gpu[0]);if(m->gpu[1])linearFree(m->gpu[1]);if(m->tex.data)C3D_TexDelete(&m->tex);memset(m,0,sizeof *m);}
bool character_load(const char *path){
    FILE *f=fopen(path,"rb");if(!f)return false;
    char magic[4];Model next={0};unsigned char *data=NULL;bool ok=false;
    if(fread(magic,1,4,f)!=4||memcmp(magic,"SGCH",4)||fread(&next.h,1,sizeof(Header),f)!=sizeof(Header))goto done;
    Header h=next.h;
    if(h.version!=1||!h.bones||h.bones>MAXB||!h.vertices||h.vertices>MAXV||h.vertices%3||!h.batches||h.batches>64||h.texture<8||h.texture>1024||(h.texture&(h.texture-1)))goto done;
    uint32_t expected=h.bones*sizeof(Bone)+h.batches*sizeof(Batch)+h.vertices*sizeof(Vertex)+h.texture*h.texture*4;
    if(h.bytes!=expected||h.bytes>8*1024*1024)goto done;
    data=malloc(h.bytes);if(!data||fread(data,1,h.bytes,f)!=h.bytes||fgetc(f)!=EOF||crc32(data,h.bytes)!=h.crc)goto done;
    next.bones=malloc(h.bones*sizeof(Bone));next.vertices=malloc(h.vertices*sizeof(Vertex));next.batch=malloc(h.batches*sizeof(Batch));
    next.gpu[0]=linearAlloc(h.vertices*sizeof(GPUVertex));next.gpu[1]=linearAlloc(h.vertices*sizeof(GPUVertex));
    if(!next.bones||!next.vertices||!next.batch||!next.gpu[0]||!next.gpu[1])goto done;
    size_t off=0;memcpy(next.bones,data,h.bones*sizeof(Bone));off+=h.bones*sizeof(Bone);
    memcpy(next.batch,data+off,h.batches*sizeof(Batch));off+=h.batches*sizeof(Batch);
    memcpy(next.vertices,data+off,h.vertices*sizeof(Vertex));off+=h.vertices*sizeof(Vertex);
    for(unsigned i=0;i<h.bones;i++){
        Bone *b=&next.bones[i];if(!memchr(b->name,0,32)||b->parent>= (int)i||b->parent < -1||!finite_array(b->local,16)||!finite_array(b->inverse,16))goto done;
    }
    unsigned total=0;for(unsigned i=0;i<h.batches;i++){Batch b=next.batch[i];if(b.start!=total||b.count%3||b.count>h.vertices-total||b.cull>2)goto done;total+=b.count;}if(total!=h.vertices)goto done;
    for(unsigned i=0;i<h.vertices;i++){
        Vertex *v=&next.vertices[i];if(!finite_array(v->p,9)||!finite_array(v->weight,4))goto done;
        float sum=0;for(int k=0;k<4;k++){if(v->bone[k]>=h.bones||v->weight[k]<0||v->weight[k]>1)goto done;sum+=v->weight[k];}if(fabsf(sum-1)>0.01f)goto done;
    }
    if(!C3D_TexInit(&next.tex,h.texture,h.texture,GPU_RGBA8))goto done;
    C3D_TexUpload(&next.tex,data+off);C3D_TexSetFilter(&next.tex,GPU_LINEAR,GPU_LINEAR);C3D_TexSetWrap(&next.tex,GPU_CLAMP_TO_EDGE,GPU_CLAMP_TO_EDGE);
    C3D_FrameSync();release(&model);model=next;memset(&next,0,sizeof next);
    for(unsigned i=0;i<h.bones;i++){Bone *b=&model.bones[i];if(b->parent<0)memcpy(rest[i],b->local,64);else mul(rest[i],rest[b->parent],b->local);}
    memset(pose,0,sizeof pose);memset(from_pose,0,sizeof from_pose);last_state=-1;selected=-1;nm=0;revision=0;
    snprintf(status,sizeof status,"Character: %lu bones / %lu tris",(unsigned long)h.bones,(unsigned long)h.vertices/3);ok=true;
done:free(data);fclose(f);release(&next);return ok;
}
bool character_motions(const char *path){
    FILE *f=fopen(path,"r");if(!f)return false;
    char line[192];unsigned ver,skel,rev,count;Motion next[MAXM];
    bool ok=fgets(line,sizeof line,f)&&sscanf(line,"SGMO %u %u %u %u",&ver,&skel,&rev,&count)==4&&ver==1&&skel==model.h.skeleton&&count>0&&count<=MAXM;
    for(unsigned i=0;ok&&i<count;i++){
        Motion *m=&next[i];ok=fgets(line,sizeof line,f)&&sscanf(line,"%d %f %f %f %f %f %f %f",&m->state,&m->duration,&m->speed,&m->intensity,&m->sway,&m->head,&m->arm,&m->phase)==8;
        if(!ok)break;
        ok=m->state>=0&&m->state<=5&&finite_array(&m->duration,7)&&m->duration>=2&&m->duration<=30&&m->speed>=.2f&&m->speed<=2&&m->intensity>=0&&m->intensity<=1&&m->sway>=0&&m->sway<=.2f&&m->head>=0&&m->head<=.5f&&m->arm>=0&&m->arm<=1.5f&&m->phase>=0&&m->phase<=6.284f;
    }
    if(ok){bool states[6]={0};for(unsigned i=0;i<count;i++)states[next[i].state]=true;for(int i=0;i<6;i++)ok=ok&&states[i];}
    if(ok){memcpy(motions,next,count*sizeof(Motion));nm=count;revision=rev;selected=-1;last_state=-1;}fclose(f);return ok;
}
bool character_init(const char *path){
    dvlb=DVLB_ParseFile((u32*)character_shader,character_shader_size);if(!dvlb)return false;
    shaderProgramInit(&program);shaderProgramSetVsh(&program,&dvlb->DVLE[0]);projection_loc=shaderInstanceGetUniformLocation(program.vertexShader,"projection");
    Mtx_OrthoTilt(&projection,-.64f,.64f,-.45f,.45f,.01f,10.f,false);shader_ready=true;
    return character_load(path);
}
static void angles(const char *name,CharacterState state,float t,const Motion *m,float *a){
    a[0]=a[1]=a[2]=0;float s=sinf(t*m->speed*1.5f+m->phase),q=sinf(t*m->speed*.63f+m->phase),amp=m->intensity;
    if(!strcmp(name,"upper_arm.L"))a[2]=-1.18f;
    if(!strcmp(name,"upper_arm.R"))a[2]=1.18f;
    if(!strcmp(name,"chest")){a[2]=q*m->sway;amp*=.4f;a[0]=s*.025f*amp;}
    if(!strcmp(name,"head")){a[1]=q*m->head*.4f;a[0]=s*m->head*.3f;if(state==CHAR_FOCUS)a[0]+=.12f;if(state==CHAR_SLEEP)a[2]=.18f;}
    if(strstr(name,"hair.")){a[0]=s*.04f*amp;a[2]=q*m->sway*.4f;}
    if(state==CHAR_SUCCESS||state==CHAR_ENCOURAGE){
        if(!strcmp(name,"upper_arm.L"))a[2]+=m->arm*(.6f+.25f*s)*amp;
        if(!strcmp(name,"forearm.L"))a[2]=.5f*amp;
        if(!strcmp(name,"hand.L"))a[1]=s*.4f*amp;
        if(state==CHAR_SUCCESS&&!strcmp(name,"upper_arm.R"))a[2]-=m->arm*.6f*amp;
    }
    if(state==CHAR_BREAK&&!strcmp(name,"chest"))a[2]+=s*.08f*amp;
    if(state==CHAR_SLEEP&&!strcmp(name,"chest"))a[0]=.06f+s*.01f;
}
static void rotate(float *m,const float *a){
    float x=a[0],y=a[1],z=a[2],cx=cosf(x),sx=sinf(x),cy=cosf(y),sy=sinf(y),cz=cosf(z),sz=sinf(z);
    ident(m);m[0]=cz*cy;m[1]=cz*sy*sx-sz*cx;m[2]=cz*sy*cx+sz*sx;
    m[4]=sz*cy;m[5]=sz*sy*sx+cz*cx;m[6]=sz*sy*cx-cz*sx;m[8]=-sy;m[9]=cy*sx;m[10]=cy*cx;
}
void character_draw(CharacterState state,float seconds){
    if(!shader_ready||!model.h.bones)return;
    if(last_state!=(int)state||seconds>=until||selected<0){
        memcpy(from_pose,pose,sizeof pose);changed=seconds;last_state=state;
        int options[MAXM],n=0;for(unsigned i=0;i<nm;i++)if(motions[i].state==(int)state&&(int)i!=selected)options[n++]=i;
        selected=n?options[random32()%n]:-1;until=seconds+(selected>=0?motions[selected].duration:8);
    }
    Motion fallback={(int)state,8,1,.4f,.04f,.18f,.8f,0};const Motion *m=selected>=0?&motions[selected]:&fallback;
    float blend=fminf(1,fmaxf(0,(seconds-changed)/.8f));blend=blend*blend*(3-2*blend);
    for(unsigned i=0;i<model.h.bones;i++){
        float a[3],r[16],basis[16],invbasis[16],localr[16],tmp[16],local[16];
        angles(model.bones[i].name,state,seconds,m,a);for(int k=0;k<3;k++)pose[i][k]=from_pose[i][k]*(1-blend)+a[k]*blend;
        rotate(r,pose[i]);ident(basis);ident(invbasis);
        // Convert semantic model-space rotation into each bone's rest axes, including bone roll.
        for(int row=0;row<3;row++)for(int col=0;col<3;col++){float len=0;for(int k=0;k<3;k++)len+=rest[i][k*4+col]*rest[i][k*4+col];float v=rest[i][row*4+col]/sqrtf(fmaxf(len,.000001f));basis[row*4+col]=v;invbasis[col*4+row]=v;}
        mul(tmp,invbasis,r);mul(localr,tmp,basis);mul(local,model.bones[i].local,localr);
        int p=model.bones[i].parent;if(p<0)memcpy(world[i],local,64);else mul(world[i],world[p],local);
        mul(skin[i],world[i],model.bones[i].inverse);
    }
    GPUVertex *dst=model.gpu[(buffer_idx++)&1];
    for(unsigned i=0;i<model.h.vertices;i++){
        Vertex *v=&model.vertices[i];memcpy(dst[i].uv,v->uv,8);memcpy(dst[i].color,v->color,16);
        for(int row=0;row<3;row++){float out=0;for(int k=0;k<4;k++){const float *mat=skin[v->bone[k]];out+=v->weight[k]*(mat[row*4]*v->p[0]+mat[row*4+1]*v->p[1]+mat[row*4+2]*v->p[2]+mat[row*4+3]);}dst[i].p[row]=out;}
        // Place character on the right; runtime is true 3D with depth and texture alpha.
        dst[i].p[0]=dst[i].p[0]*.88f+.32f;dst[i].p[1]=dst[i].p[1]*.88f-.025f;dst[i].p[2]=dst[i].p[2]*.88f-2.f;
    }
    C2D_Flush();C3D_BindProgram(&program);
    C3D_AttrInfo *attrs=C3D_GetAttrInfo();AttrInfo_Init(attrs);AttrInfo_AddLoader(attrs,0,GPU_FLOAT,3);AttrInfo_AddLoader(attrs,1,GPU_FLOAT,2);AttrInfo_AddLoader(attrs,2,GPU_FLOAT,4);
    C3D_BufInfo *buf=C3D_GetBufInfo();BufInfo_Init(buf);BufInfo_Add(buf,dst,sizeof(GPUVertex),3,0x210);
    C3D_FVUnifMtx4x4(GPU_VERTEX_SHADER,projection_loc,&projection);C3D_TexBind(0,&model.tex);
    for(int i=0;i<6;i++)C3D_TexEnvInit(C3D_GetTexEnv(i));
    C3D_TexEnv *env=C3D_GetTexEnv(0);C3D_TexEnvSrc(env,C3D_Both,GPU_TEXTURE0,GPU_PRIMARY_COLOR,0);C3D_TexEnvFunc(env,C3D_Both,GPU_MODULATE);
    C3D_AlphaTest(true,GPU_GREATER,8);C3D_DepthTest(true,GPU_GREATER,GPU_WRITE_ALL);
    for(unsigned i=0;i<model.h.batches;i++){Batch b=model.batch[i];C3D_CullFace(b.cull==1?GPU_CULL_BACK_CCW:b.cull==2?GPU_CULL_FRONT_CCW:GPU_CULL_NONE);C3D_DrawArrays(GPU_TRIANGLES,b.start,b.count);}
    C3D_AlphaTest(false,GPU_ALWAYS,0);C3D_CullFace(GPU_CULL_NONE);C2D_Prepare();
}
void character_fini(void){C3D_FrameSync();release(&model);if(shader_ready){shaderProgramFree(&program);DVLB_Free(dvlb);}shader_ready=false;}
uint32_t character_crc(void){return model.h.crc;}
uint32_t character_skeleton(void){return model.h.skeleton;}
uint32_t character_revision(void){return revision;}
const char *character_status(void){return status;}
