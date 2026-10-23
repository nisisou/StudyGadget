// Study Gadget v2 (citro2d). Modes/subjects are table-driven: add a row to MODES to add a mode.
#include <3ds.h>
#include <citro2d.h>
#include "character.h"
#include "character_sync.h"
#include "network_client.h"
#include "app_update.h"
#include "dev_relaunch.h"
// DEV_WORKFLOW_AUTOMATION
const char studygadget_application_marker[] __attribute__((used)) = SG_APP_MARKER;
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <errno.h>
#include <math.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <malloc.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define DIR_ "/3ds/studygadget/"
#define CFG  DIR_ "config.txt"      // "<IPv4|auto> <port> <token>"; auto = phone hotspot gateway
#define SUBF DIR_ "subjects.txt"    // lines "id,label,RRGGBB" (phone pushes this)
#define LOG  DIR_ "sessions.jsonl"
#define PEND DIR_ "pending.jsonl"
#define UPLOAD DIR_ "pending.upload"
#define C(r,g,b) C2D_Color32(r,g,b,255)
#define WHITE C(255,255,255)
#define MUTED C(140,150,180)
#define PANEL C(36,42,68)
#define GREEN C(74,222,128)

typedef struct { char id[16], label[24]; u32 col; } Subject;
typedef struct { const char *name; int mins; bool up; } ModeDef;   // <- extend here
static const ModeDef MODES[] = {{"TIMER",25,false},{"POMODORO",25,false},{"STOPWATCH",0,true}};
#define NMODES 3
enum { POMO_WORK = 25, POMO_SHORT = 5, POMO_LONG = 15 };

static Subject S[8], active_subject; static int ns = 0, cur = 0;
static char ip[64], token[128]; static int port; static bool cfg_ok, soc_ok, net_ok;
static char connected_peer[INET_ADDRSTRLEN] = "";
static int mode = 0, mins = 25, cycle = 0, flash = 0;
static bool running = false, brk = false;
static u64 t0; static time_t wall; static long target = 1500, today = 0;
static bool tap; static int tx, ty;
static C2D_TextBuf tb; static char msg[64] = "";
static u64 last_character_sync;
static u64 character_clock,success_until;
static char boot_stage[64]="UI ready; loading SD settings";
static char network_status[96]="NET: waiting for UI";
static bool boot_ready, safe_mode, character_visible=true;
static bool character_frame_submitted, character_frame_verified;
static int pending_count;
static u64 request_started;
typedef enum { REQUEST_NONE, REQUEST_INIT, REQUEST_CONFIG, REQUEST_LOG, REQUEST_EVENT, REQUEST_UPDATE_CHECK, REQUEST_UPDATE_FILE, REQUEST_DEV_CONTROL } RequestKind;
static RequestKind active_request;
static u64 dev_last_check,dev_restart_at,dev_command;
static bool dev_want_control,dev_automatic,dev_error_reported;
static char dev_target[65],dev_running_path[256];
static bool want_config,want_logs,want_character,want_update_check,want_update_install,update_apply_pending,exit_requested;
static FILE *upload_file;
static int uploaded_count;
static struct { char path[48], body[192]; } events[8];
static unsigned event_read,event_write;

static void boot_log(const char *message) {
    FILE *f=fopen(DIR_ "boot.log","a");
    if (f) { fprintf(f,"%llu %s\n",(unsigned long long)osGetTime(),message); fclose(f); }
}
static void boot_note(const char *message) {
    snprintf(boot_stage,sizeof boot_stage,"%s",message); boot_log(message);
}

static u32 hex(u32 h) { return C(h >> 16 & 255, h >> 8 & 255, h & 255); }

/* ---------- network ---------- */
static bool parse_subjects(const char *txt) {
    Subject next[8]={0}; int count=0; char l[96],selected_id[16]=""; const char *p=txt;
    if(ns>0)snprintf(selected_id,sizeof selected_id,"%s",S[cur].id);
    while (*p && count<8) {
        int k=0; while (*p && *p!='\n') { if(k<95)l[k++]=*p; p++; }
        l[k]=0; if(*p=='\n')p++;
        unsigned h; Subject *u=&next[count];
        if(sscanf(l,"%15[^,],%23[^,],%x",u->id,u->label,&h)==3){u->col=hex(h);count++;}
    }
    if(!count)return false;
    memcpy(S,next,count*sizeof(Subject));ns=count;cur=0;
    for(int i=0;i<ns;i++)if(!strcmp(S[i].id,selected_id)){cur=i;break;}
    return true;
}
static void load_subjects(void) {
    static char b[1024] = ""; FILE *f = fopen(SUBF, "r");
    if (f) { size_t n = fread(b, 1, sizeof b - 1, f); b[n] = 0; fclose(f); parse_subjects(b); }
    if (!ns) parse_subjects("english,ENGLISH,60A5FA\nmath,MATH,F59E0B\njapanese,JAPANESE,F472B6\n");
}
static int pending(void) {
    const char *paths[]={PEND,UPLOAD}; int count=0; char line[384];
    for(unsigned i=0;i<2;i++){
        FILE *f=fopen(paths[i],"r");if(!f)continue;
        while(fgets(line,sizeof line,f))count++;
        fclose(f);
    }
    return count;
}
static void close_upload(void) { if(upload_file){fclose(upload_file);upload_file=NULL;} }
static void queue_event(const char *path,const char *body) {
    if(!cfg_ok)return;
    if(event_write-event_read>=8){snprintf(msg,sizeof msg,"event queue full; study logs kept");return;}
    unsigned i=event_write++%8;
    snprintf(events[i].path,sizeof events[i].path,"%s",path);
    snprintf(events[i].body,sizeof events[i].body,"%s",body);
}
static void request_sync(bool logs) {
    FILE *f=fopen(CFG,"r");
    if(f){cfg_ok=fscanf(f,"%63s %d %127s",ip,&port,token)==3&&port>0&&port<=65535;fclose(f);}
    if(!cfg_ok){snprintf(network_status,sizeof network_status,"NET: set config.txt, then SYNC");return;}
    want_config=true;want_logs|=logs;want_character=true;
    snprintf(network_status,sizeof network_status,"NET: sync queued");
    if(!soc_ok&&active_request==REQUEST_NONE){
        if(network_client_initialize()){active_request=REQUEST_INIT;request_started=osGetTime();}
        else snprintf(network_status,sizeof network_status,"NET: cannot start worker; retry SYNC");
    }
}
static void dev_report(const char *state,const char *message){
    char body[192];
    snprintf(body,sizeof body,"{\"sha256\":\"%s\",\"command\":%llu,\"state\":\"%s\",\"message\":\"%s\"}",dev_target,(unsigned long long)dev_command,state,message);
    queue_event("/api/dev/report",body);
}
static bool start_request(RequestKind kind,const char *method,const char *path,const char *body) {
    if(!network_client_start(ip,port,token,method,path,body)){
        snprintf(network_status,sizeof network_status,"NET: worker failed; retry SYNC");
        boot_log(network_status);want_config=want_logs=want_character=false;close_upload();return false;
    }
    active_request=kind;request_started=osGetTime();return true;
}
static void reply_error(const NetworkReply *r) {
    net_ok=false;
    if(r->code==401||r->code==403)snprintf(network_status,sizeof network_status,"NET: HTTP %d; check pairing token",r->code);
    else if(r->code>0)snprintf(network_status,sizeof network_status,"NET: HTTP %d; retry SYNC",r->code);
    else if(r->error==ETIMEDOUT)snprintf(network_status,sizeof network_status,"NET: timed out (8s); offline ready");
    else if(r->error==ENETUNREACH)snprintf(network_status,sizeof network_status,"NET: no gateway; check Wi-Fi/hotspot");
    else if(r->error==ECONNREFUSED)snprintf(network_status,sizeof network_status,"NET: refused; start phone server");
    else snprintf(network_status,sizeof network_status,"NET: error %d; offline ready",r->error);
    boot_log(network_status);
}
static void network_tick(void) {
    static NetworkStage previous_stage=NET_IDLE;
    NetworkStage current_stage=network_client_stage();
    if(current_stage!=previous_stage){
        static const char *labels[]={"idle","initializing Wi-Fi","Wi-Fi ready","resolving phone","connecting","sending","receiving","complete","failed"};
        char line[64];snprintf(line,sizeof line,"network: %s",labels[current_stage]);boot_log(line);previous_stage=current_stage;
    }
    NetworkReply r;
    if(network_client_poll(&r)){
        RequestKind finished=active_request;active_request=REQUEST_NONE;
        if(r.initialization){
            soc_ok=r.code==200;
            if(!soc_ok){snprintf(network_status,sizeof network_status,"NET: socInit failed %08lX; retry SYNC",(unsigned long)(uint32_t)r.service_result);boot_log(network_status);return;}
            snprintf(network_status,sizeof network_status,cfg_ok?"NET: Wi-Fi ready":"NET: no config; offline ready");boot_log(network_status);
        }else{
            if(r.peer[0])snprintf(connected_peer,sizeof connected_peer,"%s",r.peer);
            net_ok=r.code==200||r.code==204;
            if(finished==REQUEST_DEV_CONTROL){
                char digest[65];unsigned version;unsigned long long command=0;
                if(r.code==200&&sscanf(r.body,"SGDEV %u %64s %llu",&version,digest,&command)==3&&version==1&&strlen(digest)==64){
                    bool valid=true;for(int i=0;i<64;i++)if(!((digest[i]>='0'&&digest[i]<='9')||(digest[i]>='a'&&digest[i]<='f')))valid=false;
                    if(valid&&app_update_state()!=APP_UPDATE_UNSUPPORTED&&(strcmp(dev_target,digest)||dev_command!=(u64)command)){
                        snprintf(dev_target,sizeof dev_target,"%s",digest);dev_command=(u64)command;dev_automatic=true;dev_error_reported=false;want_update_check=true;
                    }
                }
            }else if(finished==REQUEST_UPDATE_CHECK){
                if(r.code==200){
                    bool offered=app_update_offer(r.body);
                    if(dev_automatic){
                        if(offered&&!strcmp(app_update_sha(),dev_target))want_update_install=true;
                        else if(app_update_state()==APP_UPDATE_CURRENT){dev_report("booted","");dev_automatic=false;}
                        else if(offered){dev_report("error","Phone update changed; press update again");dev_automatic=false;}
                    }
                }
                else if(r.code==204)app_update_failed("No update on phone; import 3DSX first");
                else{reply_error(&r);app_update_failed(network_status);}
                boot_log(app_update_status());
            }else if(finished==REQUEST_UPDATE_FILE){
                if(r.code==200){update_apply_pending=true;snprintf(network_status,sizeof network_status,"NET: received; verifying application");}
                else{reply_error(&r);app_update_failed(network_status);}
            }else if(!net_ok){
                reply_error(&r);
                if(finished==REQUEST_CONFIG||finished==REQUEST_LOG){want_config=want_logs=want_character=false;close_upload();}
            }else if(finished==REQUEST_CONFIG){
                if(!parse_subjects(r.body)){
                    snprintf(network_status,sizeof network_status,"NET: invalid subject response; logs kept");
                    want_logs=want_character=false;boot_log(network_status);
                }else{
                    FILE *f=fopen(SUBF,"w");if(f){fputs(r.body,f);fclose(f);}
                    snprintf(network_status,sizeof network_status,"NET: connected; subjects updated");boot_log(network_status);
                }
            }else if(finished==REQUEST_LOG){
                uploaded_count++;snprintf(network_status,sizeof network_status,"NET: uploading logs (%d sent)",uploaded_count);
            }else snprintf(network_status,sizeof network_status,"NET: connected; event delivered");
        }
    }
    if(active_request!=REQUEST_NONE||update_apply_pending||!soc_ok||!cfg_ok)return;
    if(event_read!=event_write){
        unsigned i=event_read%8;
        if(start_request(REQUEST_EVENT,"POST",events[i].path,events[i].body))event_read++;
        return;
    }
    if(dev_want_control){
        dev_want_control=false;start_request(REQUEST_DEV_CONTROL,"GET","/api/dev/control","");return;
    }
    if(want_update_check&&!running){
        want_update_check=false;app_update_checking();
        if(!start_request(REQUEST_UPDATE_CHECK,"GET","/api/update/manifest",""))app_update_failed("Cannot start update check; retry UPDATE");
        return;
    }
    if(want_update_install&&!running){
        want_update_install=false;
        if(running){app_update_failed("Stop timer before installing an update");return;}
        char path[96];snprintf(path,sizeof path,"/api/update/3ds/%s",app_update_sha());
        if(network_client_download_start(ip,port,token,path,app_update_staging(),app_update_size(),app_update_sha())){
            active_request=REQUEST_UPDATE_FILE;request_started=osGetTime();app_update_downloading();
        }else app_update_failed("Cannot start update download");
        return;
    }
    if(want_config){
        want_config=false;start_request(REQUEST_CONFIG,"GET","/api/config","");return;
    }
    if(want_logs){
        if(!upload_file){
            // Freeze one batch. New sessions continue appending to pending.jsonl.
            // Keep the entire batch on any failure/crash; server IDs deduplicate retries.
            upload_file=fopen(UPLOAD,"r");
            if(!upload_file){
                FILE *f=fopen(PEND,"r");
                if(f){fclose(f);if(rename(PEND,UPLOAD)!=0){snprintf(network_status,sizeof network_status,"NET: SD rename failed; logs kept");want_logs=false;return;}}
                upload_file=fopen(UPLOAD,"r");
            }
            uploaded_count=0;
        }
        if(upload_file){
            char line[384];
            if(fgets(line,sizeof line,upload_file)){
                if(!strchr(line,'\n')&&!feof(upload_file)){close_upload();want_logs=false;snprintf(network_status,sizeof network_status,"NET: oversized log; logs kept");return;}
                if(!start_request(REQUEST_LOG,"POST","/api/sync",line)){close_upload();want_logs=false;}
                return;
            }
            bool read_error=ferror(upload_file);close_upload();
            if(read_error){snprintf(network_status,sizeof network_status,"NET: SD read failed; logs kept");want_logs=false;return;}
            if(remove(UPLOAD)!=0){snprintf(network_status,sizeof network_status,"NET: SD cleanup failed; retry safe");want_logs=false;return;}
            pending_count=pending();if(pending_count>0)return;
        }
        want_logs=false;pending_count=pending();
        snprintf(network_status,sizeof network_status,"NET: sync complete; %d pending",pending_count);boot_log(network_status);
    }
    if(want_character){
        want_character=false;if(!safe_mode)character_sync_start(ip,port,token);last_character_sync=osGetTime();
    }
}

/* ---------- session logic ---------- */
static void save(long dur) {
    char a[24], b[24], l[300]; struct tm tm; time_t e = time(NULL);
    localtime_r(&wall, &tm); strftime(a, 24, "%Y-%m-%dT%H:%M:%S", &tm);
    localtime_r(&e, &tm);    strftime(b, 24, "%Y-%m-%dT%H:%M:%S", &tm);
    snprintf(l, sizeof l, "{\"id\":%lld,\"subject\":\"%s\",\"start\":\"%s\",\"end\":\"%s\",\"duration\":%ld,\"device\":\"3ds\"}\n",
             (long long)wall, active_subject.id, a, b, dur);
    FILE *f = fopen(LOG, "a"); if (f) { fputs(l, f); fclose(f); }
    f = fopen(PEND, "a");      if (f) { if(fputs(l,f)>=0)pending_count++; fclose(f); }
}
static void load_today(void) {
    char d[16], l[300]; time_t t = time(NULL); struct tm tm; localtime_r(&t, &tm); strftime(d, 16, "%Y-%m-%d", &tm);
    FILE *f = fopen(LOG, "r"); if (!f) return;
    while (fgets(l, sizeof l, f)) { char *s = strstr(l, "\"start\":\""), *p = strstr(l, "\"duration\":");
        if (s && p && !strncmp(s + 9, d, 10)) today += atol(p + 11); }
    fclose(f);
}
static void set_target(void) {
    target = mode == 0 ? mins * 60 : mode == 1 ? (brk ? (cycle % 4 == 0 ? POMO_LONG : POMO_SHORT) : POMO_WORK) * 60 : 0;
}
static long elapsed(void) { return running ? (long)((osGetTime() - t0) / 1000) : 0; }
static void begin(void) {
    active_subject=S[cur]; running = true; t0 = osGetTime(); wall = time(NULL); aptSetSleepAllowed(false); msg[0] = 0;
    if (cfg_ok && !brk) { char b[96]; snprintf(b, sizeof b, "{\"subject\":\"%s\",\"timestamp\":%lld}", active_subject.id, (long long)wall);
        queue_event("/api/session/start", b); }
}
static void end_session(bool finished) {
    long d = finished ? target : elapsed(); if (MODES[mode].up) d = elapsed();
    running = false; aptSetSleepAllowed(true);
    if (!brk && d >= 10) { save(d); today += d; }
    if (cfg_ok) queue_event("/api/session/stop", "{}");
    if (cfg_ok) request_sync(true);
    if (mode == 1) { if (finished) { if (!brk) cycle++; brk = !brk; } else brk = false; set_target(); if (finished) begin(); }
    if (finished) {flash = 90;success_until=osGetTime()+10000;}
}

/* ---------- drawing ---------- */
static void text(float x, float y, float s, u32 c, u32 fl, const char *fmt, ...) {
    char b[96]; va_list v; va_start(v, fmt); vsnprintf(b, sizeof b, fmt, v); va_end(v);
    C2D_Text t; C2D_TextParse(&t, tb, b); C2D_TextOptimize(&t);
    C2D_DrawText(&t, C2D_WithColor | fl, x, y, 0.5f, s, s, c);
}
static void rrect(float x, float y, float w, float h, float r, u32 c) {
    (void)r;
    C2D_DrawRectSolid(x, y, 0.1f, w, h, c);
}
static void ring(float cx, float cy, float r, float th, float f, u32 c) {
    int n = 72, k = (int)(f * n + 0.5f);
    for (int i = 0; i < k; i++) {
        float a0 = -M_PI / 2 + 2 * M_PI * i / n, a1 = -M_PI / 2 + 2 * M_PI * (i + 1) / n;
        float x0 = cx + r * cosf(a0), y0 = cy + r * sinf(a0), x1 = cx + r * cosf(a1), y1 = cy + r * sinf(a1);
        float X0 = cx + (r - th) * cosf(a0), Y0 = cy + (r - th) * sinf(a0), X1 = cx + (r - th) * cosf(a1), Y1 = cy + (r - th) * sinf(a1);
        C2D_DrawTriangle(x0, y0, c, x1, y1, c, X0, Y0, c, 0.2f); C2D_DrawTriangle(x1, y1, c, X1, Y1, c, X0, Y0, c, 0.2f);
    }
}
static bool btn(float x, float y, float w, float h, u32 col, const char *lab, bool en, bool sel) {
    if (sel) rrect(x - 2, y - 2, w + 4, h + 4, 10, WHITE);
    rrect(x, y, w, h, 8, en ? col : C(50,54,72));
    text(x + w / 2, y + h / 2 - 9, 0.5f, en ? WHITE : MUTED, C2D_AlignCenter, "%s", lab);
    return en && tap && tx >= x && tx < x + w && ty >= y && ty < y + h;
}
static void draw_top(void) {
    long el = elapsed(), shown = MODES[mode].up ? el : (running ? target - el : target); if (shown < 0) shown = 0;
    u32 acc = brk ? GREEN : running ? active_subject.col : S[cur].col; time_t n = time(NULL); struct tm tm; localtime_r(&n, &tm);
    u32 bg = flash ? (flash / 8 % 2 ? acc : C(18,22,38)) : C(18,22,38);
    C2D_DrawRectangle(0, 0, 0, 400, 240, bg, bg, C(6,8,16), C(6,8,16));
    text(14, 8, 0.7f, WHITE, 0, "%02d:%02d", tm.tm_hour, tm.tm_min);
    text(14, 32, 0.45f, MUTED, 0, "%04d/%02d/%02d", tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday);
    text(386, 10, 0.5f, MUTED, C2D_AlignRight, "TODAY %ldh%02ldm", (today + el) / 3600, (today + el) % 3600 / 60);
    bool busy=active_request!=REQUEST_NONE;
    C2D_DrawRectSolid(375, 33, 0.2f, 10, 10, busy?(osGetTime()/300%2?C(255,200,64):PANEL):net_ok?GREEN:C(90,96,120));
    text(367,32,.35f,MUTED,C2D_AlignRight,busy?"CONNECTING":net_ok?"ONLINE":"OFFLINE");
    float f = MODES[mode].up ? (el % 3600) / 3600.f : (target ? (float)(target - (running ? el : 0)) / target : 0);
    if (!running && !MODES[mode].up) f = 1;
    ring(113, 128, 80, 10, 1, PANEL); ring(113, 128, 80, 10, f, acc);
    text(113, 96, 1.2f, WHITE, C2D_AlignCenter, "%02ld:%02ld", shown / 60, shown % 60);
    text(113, 145, 0.55f, acc, C2D_AlignCenter, "%s", brk ? "BREAK" : running ? active_subject.label : S[cur].label);
    text(113, 169, 0.4f, MUTED, C2D_AlignCenter, "%s%s", MODES[mode].name, running ? "  ●" : "");
    if (mode == 1) for (int i = 0; i < 4; i++) C2D_DrawRectSolid(85 + i * 16, 204, 0.3f, 8, 8, i < cycle % 4 ? acc : PANEL);
    CharacterState state=osGetTime()<success_until?CHAR_SUCCESS:running?(brk?CHAR_BREAK:CHAR_FOCUS):(tm.tm_hour>=23||tm.tm_hour<6)?CHAR_SLEEP:CHAR_IDLE;
    if(!running&&state==CHAR_IDLE&&today>0&&today<1500&&tm.tm_hour>=18)state=CHAR_ENCOURAGE;
    if(character_visible && boot_ready)character_draw(state,(float)(osGetTime()-character_clock)/1000.f);
    if(!character_crc()||!character_visible)text(300,210,.35f,MUTED,C2D_AlignCenter,safe_mode?"SAFE MODE: character skipped":!character_visible?"Character hidden (X)":"See CHAR status on bottom screen");
}
static const char *network_stage_name(void) {
    switch(network_client_stage()){
        case NET_INITIALIZING:return "initializing Wi-Fi";
        case NET_RESOLVING:return "finding phone";
        case NET_CONNECTING:return "connecting";
        case NET_SENDING:return "sending";
        case NET_RECEIVING:return "waiting for response";
        default:return "finishing";
    }
}
static void draw_bottom(void) {
    C2D_DrawRectangle(0,0,0,320,240,C(24,28,46),C(24,28,46),C(12,14,26),C(12,14,26));
    text(8,2,.35f,MUTED,0,"STUDY GADGET 2.3   %s",msg);
    if(!boot_ready)text(8,16,.4f,C(255,200,64),0,"BOOT: %s",boot_stage);
    else if(active_request!=REQUEST_NONE)text(8,16,.4f,C(255,200,64),0,"NET: %s (%llus)",network_stage_name(),(unsigned long long)((osGetTime()-request_started)/1000));
    else text(8,16,.4f,net_ok?GREEN:MUTED,0,"%s",network_status);
    CharacterSyncProgress progress=character_sync_progress();
    AppUpdateState update=app_update_state();
    if(update==APP_UPDATE_DOWNLOADING){
        NetworkProgress transfer=network_client_progress();
        text(8,30,.35f,C(255,200,64),0,"APP: %lu/%lu KB (B: cancel)",(unsigned long)transfer.received/1024,(unsigned long)transfer.total/1024);
    }else if(update!=APP_UPDATE_IDLE)text(8,30,.35f,MUTED,0,"APP: %s",app_update_status());
    else if(progress.stage==CHAR_SYNC_MODEL||progress.stage==CHAR_SYNC_MOTIONS)
        text(8,30,.35f,C(255,200,64),0,"CHAR: %s %lu/%lu KB",progress.stage==CHAR_SYNC_MODEL?"model":"motions",(unsigned long)progress.received/1024,(unsigned long)progress.total/1024);
    else if(progress.stage==CHAR_SYNC_ERROR && progress.http_code>0 && progress.http_code!=200)
        text(8,30,.35f,MUTED,0,"CHAR: HTTP %d; files kept; retry SYNC",progress.http_code);
    else text(8,30,.35f,MUTED,0,"CHAR: %s",safe_mode?"skipped in safe mode":!character_crc()?character_status():character_sync_status());
    for(int i=0;i<ns;i++)if(btn(8+(i%3)*104,48+(i/3)*24,96,20,S[i].col,S[i].label,!running,i==cur))cur=i;
    for(int i=0;i<NMODES;i++)
        if(btn(8+i*104,122,96,24,C(70,78,120),MODES[i].name,!running,i==mode)){mode=i;brk=false;if(i==0)mins=MODES[0].mins;set_target();}
    if(mode==0){
        if(btn(8,152,60,24,PANEL,"-5",!running,false)&&mins>5){mins-=5;set_target();}
        text(160,153,.65f,WHITE,C2D_AlignCenter,"%d min",mins);
        if(btn(252,152,60,24,PANEL,"+5",!running,false)&&mins<180){mins+=5;set_target();}
    }else text(160,155,.5f,MUTED,C2D_AlignCenter,mode==1?"25 / 5 x4, then 15":"free count-up");
    bool updating=update==APP_UPDATE_DOWNLOADING||update==APP_UPDATE_CHECKING;
    if(btn(8,184,152,36,running?C(220,80,80):GREEN,running?"STOP":"START",boot_ready&&!updating,false))tap=false,running?end_session(false):begin();
    if(btn(168,184,68,36,PANEL,"SYNC",boot_ready&&active_request==REQUEST_NONE&&!updating,false))request_sync(true);
    const char *update_label=update==APP_UPDATE_AVAILABLE?"INSTALL":update==APP_UPDATE_INSTALLED?"EXIT":"UPDATE";
    if(btn(244,184,68,36,PANEL,update_label,boot_ready&&!running&&!updating&&update!=APP_UPDATE_UNSUPPORTED,false)){
        if(update==APP_UPDATE_INSTALLED)exit_requested=true;
        else if(update==APP_UPDATE_AVAILABLE)want_update_install=true;
        else{request_sync(false);if(cfg_ok)want_update_check=true;else app_update_failed("Set phone config.txt before UPDATE");}
    }
    text(312,224,.35f,MUTED,C2D_AlignRight,"pending %d",pending_count);
    text(8,224,.35f,MUTED,0,"PHONE %s%s",!strcmp(ip,"auto")?"AUTO ":"",connected_peer[0]?connected_peer:"--");
}
static int raw_bottom_test(void) {
    u16 width=0,height=0;
    if(!gfxGetFramebuffer(GFX_BOTTOM,GFX_LEFT,&width,&height)||!width||!height)return 1;
    gfxSetDoubleBuffering(GFX_BOTTOM,false);
    u8 *framebuffer=gfxGetFramebuffer(GFX_BOTTOM,GFX_LEFT,NULL,NULL);
    if(!framebuffer)return 1;
    size_t bytes=(size_t)width*height*gspGetBytesPerPixel(gfxGetScreenFormat(GFX_BOTTOM));
    u64 next_color=0;bool white=false;
    while(aptMainLoop()){
        hidScanInput();
        if(hidKeysDown()&KEY_SELECT)break;
        if(osGetTime()>=next_color){
            white=!white;
            next_color=osGetTime()+1000;
            memset(framebuffer,white?0xFF:0,bytes);
            gfxFlushBuffers();
            gfxScreenSwapBuffers(GFX_BOTTOM,false);
        }
        gspWaitForVBlank();
    }
    return 0;
}
// NONBLOCK skips Citro3D's unbounded GPU queue wait. VBlank alone is not a GPU fence.
static bool begin_gpu_frame(void) {
    u64 started=osGetTime();
    do {
        if(C3D_FrameBegin(C3D_FRAME_NONBLOCK))return true;
        svcSleepThread(1000000);
    } while(osGetTime()-started<3000);
    return false;
}
static int graphics_failure(const char *reason) {
    boot_log(reason);consoleInit(GFX_BOTTOM,NULL);
    printf("\x1b[2JStudy Gadget startup error\n\n%s\n\nSee /3ds/studygadget/boot.log\nSELECT: return to HBL\n",reason);
    while(aptMainLoop()){
        hidScanInput();if(hidKeysDown()&KEY_SELECT)break;
        gfxFlushBuffers();gfxSwapBuffers();gspWaitForVBlank();
    }
    return 1;
}

int main(int argc,char **argv) {
    gfxInitDefault();
    hidScanInput();
    if(hidKeysHeld()&KEY_START){int result=raw_bottom_test();gfxExit();return result;}
    mkdir("/3ds",0777);mkdir(DIR_,0777);
    remove(DIR_ "boot.previous.log");rename(DIR_ "boot.log",DIR_ "boot.previous.log");
    boot_log("entry: graphics initialization");
    if(!C3D_Init(C3D_DEFAULT_CMDBUF_SIZE))return graphics_failure("C3D_Init failed");
    boot_log("C3D_Init OK");
    if(!C2D_Init(C2D_DEFAULT_MAX_OBJECTS))return graphics_failure("C2D_Init failed");
    C2D_Prepare();boot_log("C2D_Init OK");
    C3D_RenderTarget *top=C2D_CreateScreenTarget(GFX_TOP,GFX_LEFT);
    if(!top)return graphics_failure("Top render target failed");
    {
        char line[96];
        snprintf(line,sizeof line,"graphics: Citro2D top target; build %s %s",__DATE__,__TIME__);
        boot_log(line);
    }
    C3D_RenderTarget *bot=C2D_CreateScreenTarget(GFX_BOTTOM,GFX_LEFT);
    tb=C2D_TextBufNew(4096);
    if(!bot||!tb)return graphics_failure("UI memory allocation failed");
    parse_subjects("english,ENGLISH,60A5FA\nmath,MATH,F59E0B\njapanese,JAPANESE,F472B6\n");set_target();
    int boot_step=0,boot_wait=2;bool startup_done=false;
    while(aptMainLoop()){
        hidScanInput();u32 kd=hidKeysDown();touchPosition tp;hidTouchRead(&tp);
        if((kd&KEY_SELECT)||exit_requested)break;
        if(!boot_ready){
            if(hidKeysHeld()&KEY_X)safe_mode=true;
        }
        else{
            if(kd&KEY_X)character_visible=!character_visible;
            network_tick();
            if(soc_ok&&cfg_ok&&osGetTime()-dev_last_check>=5000&&!update_apply_pending&&
               app_update_state()!=APP_UPDATE_DOWNLOADING&&app_update_state()!=APP_UPDATE_CHECKING){
                dev_last_check=osGetTime();dev_want_control=true;
            }
            if(dev_automatic&&app_update_state()==APP_UPDATE_ERROR&&!dev_error_reported){
                dev_report("error","Update failed; see 3DS APP status");dev_error_reported=true;dev_automatic=false;
            }
            if(dev_restart_at&&!running&&active_request==REQUEST_NONE&&event_read==event_write&&osGetTime()>=dev_restart_at){
                dev_restart_at=0;
                if(dev_relaunch_prepare(dev_running_path)){boot_log("Development update: relaunch prepared");exit_requested=true;}
                else snprintf(msg,sizeof msg,"Updated; EXIT and reopen in HBL");
            }
            if(soc_ok&&cfg_ok&&osGetTime()-last_character_sync>600000){want_character=true;last_character_sync=osGetTime();}
            bool updating=app_update_state()==APP_UPDATE_DOWNLOADING||app_update_state()==APP_UPDATE_CHECKING;
            if((kd&KEY_A)&&!updating)running?end_session(false):begin();
            if((kd&KEY_B)&&app_update_state()==APP_UPDATE_DOWNLOADING){
                if(update_apply_pending){update_apply_pending=false;remove(app_update_staging());app_update_failed("Update cancelled; current file kept");}
                else network_client_cancel();
            }
            else if((kd&KEY_B)&&running)end_session(false);
            if(!running){if(kd&KEY_Y)cur=(cur+1)%ns;if(kd&KEY_R){mins=mins<180?mins+5:mins;set_target();}if(kd&KEY_L){mins=mins>5?mins-5:mins;set_target();}}
            if(running&&!MODES[mode].up&&elapsed()>=target)end_session(true);
        }
        tap=boot_ready&&(kd&KEY_TOUCH);tx=tp.px;ty=tp.py;
        if(flash)flash--;
        if(!begin_gpu_frame()){
            char reason[192];
            snprintf(reason,sizeof reason,
                "GPU timeout (3s); boot=%d safe=%d char=%d net=%s",
                boot_ready?1:0,safe_mode?1:0,character_visible?1:0,network_stage_name());
            return graphics_failure(reason);
        }
        if(character_frame_submitted&&!character_frame_verified){
            character_frame_verified=true;boot_log("character: first GPU frame completed");
        }
        // The previous GPU queue is now finished. Load/replace/free models before
        // submitting this frame's draws; never free a VBO still used by the GPU.
        if(boot_ready&&character_sync_poll())snprintf(msg,sizeof msg,"character updated");
        if(!startup_done&&boot_wait--<=0){
            boot_wait=2;
            switch(boot_step++){
                case 0:{
                    boot_log("UI first frames submitted; network not started");
                    FILE *f=fopen(CFG,"r");
                    if(f){cfg_ok=fscanf(f,"%63s %d %127s",ip,&port,token)==3&&port>0&&port<=65535;fclose(f);}
                    load_subjects();load_today();pending_count=pending();
                    app_update_init(argc>0?argv[0]:NULL,envIsHomebrew());
                    snprintf(dev_running_path,sizeof dev_running_path,"%s",argc>0&&argv[0]?argv[0]:"sdmc:/3ds/studygadget/studygadget.3dsx");
                    boot_note("Loading character (hold X to skip)");break;
                }
                case 1:
                    if(!safe_mode){
                        boot_log("character: initialization begin");
                        character_init(DIR_ "model.sgch");
                        if(!character_crc())character_load(DIR_ "model.previous");
                        boot_log(character_status());
                    }else character_visible=false;
                    boot_note(safe_mode?"Character skipped; starting offline":"Loading motions");break;
                case 2:
                    if(!safe_mode&&!character_motions(DIR_ "motions.sgmo"))
                        if(!character_motions(DIR_ "motions.previous"))boot_log("character: no valid motions; using built-in motion");
                    boot_note("Motions ready; preparing first character frame");break;
                case 3:
                    character_clock=last_character_sync=osGetTime();boot_ready=true;
                    boot_log(character_status());
                    boot_note("UI ready; testing character frame");break;
                case 4:
                    startup_done=true;
                    if(network_client_initialize()){active_request=REQUEST_INIT;request_started=osGetTime();want_config=want_character=cfg_ok;}
                    else snprintf(network_status,sizeof network_status,"NET: worker failed; retry SYNC");
                    boot_log("startup complete; asynchronous network scheduled");break;
            }
        }
        if(boot_ready&&character_visible&&character_crc()&&!character_frame_submitted)
            boot_log("character: first draw begin");
        C2D_TextBufClear(tb);
        C2D_TargetClear(top,C(0,0,0));C2D_SceneBegin(top);draw_top();
        C2D_TargetClear(bot,C(0,0,0));C2D_SceneBegin(bot);draw_bottom();
        C3D_FrameEnd(0);
        if(boot_ready&&character_visible&&character_crc()&&!character_frame_submitted){
            character_frame_submitted=true;boot_log("character: first GPU frame submitted");
        }
        gspWaitForVBlank();
        if(update_apply_pending&&!running){
            update_apply_pending=false;bool installed=app_update_install();boot_log(app_update_status());
            if(dev_automatic){
                dev_report(installed?"installed":"error",installed?"":"SD install failed; original kept");
                if(installed)dev_restart_at=osGetTime()+2000;
                dev_automatic=false;
            }
        }

    }
    if(running)end_session(false);
    close_upload();character_sync_stop();network_client_shutdown();
    if(!begin_gpu_frame())return graphics_failure("GPU timeout during exit; resources kept until process exits");
    character_fini();C3D_FrameEnd(0);
    C2D_TextBufDelete(tb);C2D_Fini();C3D_Fini();gfxExit();return 0;
}
