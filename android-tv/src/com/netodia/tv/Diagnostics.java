package com.netodia.tv;

import android.content.*;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Bounded app diagnostics. No session, cookies, queries or media URLs are recorded. */
final class Diagnostics {
  private static boolean initialized;
  static synchronized void initialize(Context context) {
    if(initialized)return;initialized=true;
    Context app=context.getApplicationContext();
    Thread.UncaughtExceptionHandler original=Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
      StringBuilder detail=new StringBuilder("crash ").append(error.getClass().getName());
      for(StackTraceElement frame:error.getStackTrace())detail.append('\n').append(frame);
      record(app,detail.toString(),false);
      if(original!=null)original.uncaughtException(thread,error);
    });
    record(app,"application started",false);
  }
  static File file(Context context){return new File(context.getFilesDir(),"diagnostics/events.log");}
  static synchronized void record(Context context,String event,boolean verbose) {
    if(verbose&&!context.getSharedPreferences("music",0).getBoolean("verbose_logging",false))return;
    try{
      File target=file(context);target.getParentFile().mkdirs();
      if(target.length()>256*1024){String old=read(context);byte[] tail=old.substring(Math.max(0,old.length()-48000)).getBytes(StandardCharsets.UTF_8);try(FileOutputStream out=new FileOutputStream(target)){out.write(tail);}}
      String line=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date())+" "+event+"\n";
      try(FileOutputStream out=new FileOutputStream(target,true)){out.write(line.getBytes(StandardCharsets.UTF_8));}
    }catch(IOException ignored){}
  }
  static synchronized String read(Context context){
    try{return new String(java.nio.file.Files.readAllBytes(file(context).toPath()),StandardCharsets.UTF_8);}catch(IOException e){return "暂无日志。";}
  }
  static String report(Context context){
    String version="";try{version=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception ignored){}
    StringBuilder text=new StringBuilder("Netodia TV diagnostics\nVersion: ").append(version).append("\nDevice: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\nAndroid: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\nSettings\n");
    SharedPreferences prefs=context.getSharedPreferences("music",0);
    for(String key:new String[]{"theme","variant","ui_scale","quality","lyrics_enabled","lyrics_online","lyrics_romanization","lyric_translation","lyric_size","output_device","verbose_logging"})text.append(key).append('=').append(prefs.getAll().get(key)).append('\n');
    return text.append("\nEvents (credentials and media URLs excluded)\n").append(read(context)).toString();
  }
  static String save(Context context) throws IOException {
    byte[] bytes=report(context).getBytes(StandardCharsets.UTF_8);
    String name="Netodia-TV-diagnostics-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".txt";
    if(Build.VERSION.SDK_INT>=29){
      ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,name);values.put(MediaStore.Downloads.MIME_TYPE,"text/plain");values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/Netodia");values.put(MediaStore.Downloads.IS_PENDING,1);
      android.net.Uri uri=context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
      if(uri==null)throw new IOException("无法创建诊断报告");
      try{try(OutputStream out=context.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("无法写入报告");out.write(bytes);}values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);context.getContentResolver().update(uri,values,null,null);}catch(IOException error){context.getContentResolver().delete(uri,null,null);throw error;}
      return "下载/Netodia/"+name;
    }
    File target=new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),name);try(FileOutputStream out=new FileOutputStream(target)){out.write(bytes);}return target.getAbsolutePath();
  }
}
