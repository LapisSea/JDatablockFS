package com.lapissea.fsf;

public class BitDepthOutOfSpaceException extends Throwable{
	public final NumberSize numberSize;
	public final long       num;
	
	public BitDepthOutOfSpaceException(NumberSize numberSize, long num){
		super(num+" can't fit in to "+numberSize);
		this.numberSize=numberSize;
		this.num=num;
	}
}
