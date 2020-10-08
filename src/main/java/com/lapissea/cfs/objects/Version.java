package com.lapissea.cfs.objects;

import com.lapissea.cfs.exceptions.MalformedFileException;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.util.TextUtil;

import java.io.IOException;

public enum Version{
	V01(0, 1);
	
	public static Version last(){
		var vs=Version.values();
		return vs[vs.length-1];
	}
	
	public static Version read(ContentReader in) throws IOException{
		var nums=in.readInts2(2);
		for(Version value : values()){
			if(value.is(nums)) return value;
		}
		throw new MalformedFileException(TextUtil.toString("Unknown version:", nums));
	}
	
	public final byte major;
	public final byte minor;
	
	Version(int major, int minor){
		this.major=(byte)major;
		this.minor=(byte)minor;
	}
	
	public boolean is(short[] versionBytes){
		return versionBytes[0]==major&&versionBytes[1]==minor;
	}
	
}
