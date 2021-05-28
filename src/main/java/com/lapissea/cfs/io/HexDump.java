package com.lapissea.cfs.io;

import java.awt.*;
import java.io.IOException;
import java.util.BitSet;

public class HexDump{
	
	private static final BitSet DISPLAYABLE=new BitSet(256);
	
	public static int DEFAULT_MAX_WIDTH=32;
	
	static{
		Font font=new Font("SERIF", Font.PLAIN, 1);
		for(int i=0;i<DISPLAYABLE.size();i++){
			DISPLAYABLE.set(i, font.canDisplay(i));
		}
	}
	
	public static StringBuilder hexDump(RandomIO data, String title, int maxWidth) throws IOException{
		
		long size=data.getSize();
		
		int    digits=String.valueOf(size).length();
		String format="%0"+digits+"d/%0"+digits+"d: ";
		
		StringBuilder result=new StringBuilder().append("hexdump: ").append(title).append("\n");
		
		int numlen=format.formatted(0, 0).length();
		int round =8;
		int space =result.length()-numlen;
		int width =Math.min(Math.max((int)Math.round((Math.max(space/4.0, Math.sqrt(size))/(double)round)), 1)*round, maxWidth);
		
		int cap=result.length()+width*((int)Math.ceil((size/(double)width))*4+numlen+1);
		
		result.ensureCapacity(cap);
		
		
		
		long   read=0;
		byte[] line=new byte[width];
		
		while(true){
			long remaining=size-read;
			if(remaining==0) return result;
			
			
			int lineSiz=(int)Math.min(remaining, line.length);
			
			result.append(format.formatted(read, read+lineSiz));
			
			read+=lineSiz;
			data.readFully(line, 0, lineSiz);
			
			
			for(int i=0;i<line.length;i++){
				if(i>=lineSiz){
					result.append("  ");
				}else{
					var ay=Integer.toHexString(line[i]&0xFF).toUpperCase();
					if(ay.length()==1) result.append('0');
					result.append(ay);
				}
				result.append(' ');
			}
			
			for(int i=0;i<lineSiz;i++){
				char c=(char)line[i];
				
				result.append((char)switch(c){
					case 0 -> '␀';
					case '\n' -> '↵';
					case '\t' -> '↹';
//					case ' ' -> '⌴';
					default -> DISPLAYABLE.get(c)?c:'·';
				});
			}
			if(read<size) result.append('\n');
			
		}
	}
	
}
