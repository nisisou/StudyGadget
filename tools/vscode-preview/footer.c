__declspec(dllexport) void sg_reset(void){
    ns=0;cur=0;mode=0;mins=25;cycle=0;flash=0;running=false;brk=false;
    today=0;pending_count=0;success_until=0;character_visible=true;cfg_ok=false;
    boot_ready=true;soc_ok=true;net_ok=false;active_request=REQUEST_NONE;
    snprintf(network_status,sizeof network_status,"NET: simulated (no phone traffic)");
    snprintf(msg,sizeof msg,"UI PREVIEW");
    parse_subjects("english,ENGLISH,60A5FA\nmath,MATH,F59E0B\njapanese,JAPANESE,F472B6\n");
    set_target();character_clock=preview_now;
}
__declspec(dllexport) const char *sg_frame(uint64_t now,int x,int y,int key,int connection){
    preview_now=now;net_ok=connection==1;
    if(key==1)running?end_session(false):begin();
    if(key==2&&running)end_session(false);
    if(key==3)character_visible=!character_visible;
    if(!running){
        if(key==4)cur=(cur+1)%ns;
        if(key==5){mins=mins<180?mins+5:mins;set_target();}
        if(key==6){mins=mins>5?mins-5:mins;set_target();}
    }
    if(running&&!MODES[mode].up&&elapsed()>=target)end_session(true);
    tap=x>=0&&y>=0;tx=x;ty=y;if(flash)flash--;
    json_used=0;out("{\"ops\":[");screen_id=0;draw_top();screen_id=1;draw_bottom();
    if(json_used&&frame_json[json_used-1]==',')frame_json[--json_used]=0;
    out("],\"running\":%s,\"mins\":%d,\"mode\":%d,\"subject\":%d,\"today\":%ld,\"pending\":%d}",running?"true":"false",mins,mode,cur,today,pending_count);
    return frame_json;
}

