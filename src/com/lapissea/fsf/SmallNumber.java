package com.lapissea.fsf;

import com.lapissea.fsf.io.ContentReader;
import com.lapissea.fsf.io.ContentWriter;
import com.lapissea.fsf.io.serialization.FileObject;

import java.io.IOException;
import java.util.Arrays;

public class SmallNumber extends FileObject{
	
	public static int bytes(int number){
		return number/0xFF+1;
	}
	
	public static void writeNum(ContentWriter dest, int number) throws IOException{
		int    byteFill=number/0xFF;
		byte[] fill    =new byte[byteFill];
		Arrays.fill(fill, (byte)0xFF);
		dest.write(fill);
		dest.write(number-byteFill*0xFF);
	}
	
	public static int readNum(ContentReader src) throws IOException{
		int number=0, b;
		while((b=src.readInt1())==0xFF) number+=b;
		number+=b;
		return number;
	}
	
	private int value;
	
	public SmallNumber(int value){
		this.value=value;
	}
	
	@Override
	public void read(ContentReader dest) throws IOException{
		value=readNum(dest);
	}
	
	@Override
	public void write(ContentWriter dest) throws IOException{
		writeNum(dest, getValue());
	}
	
	@Override
	public long length(){
		return bytes(getValue());
	}
	
	public int getValue(){
		return value;
	}
}
