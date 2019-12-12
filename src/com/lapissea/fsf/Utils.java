package com.lapissea.fsf;

import java.io.IOException;
import java.io.OutputStream;

class Utils{
	
	public static void zeroFill(OutputStream dest, int size) throws IOException{
		var  part=new byte[Math.min(size, 1024)];
		long left=size;
		while(left>0){
			int write=(int)Math.min(left, part.length);
			dest.write(part, 0, write);
			left-=write;
		}
	}
	
}
