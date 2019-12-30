package com.lapissea.fsf;

public class BitDepthOutOfSpaceException extends Throwable{
	public final NumberSize numberSize;
	public final long       num;
	
	public BitDepthOutOfSpaceException(NumberSize numberSize, long num){
		this.numberSize=numberSize;
		this.num=num;
	}
}
