package com.lapissea.fsf;

public class BitDepthOutOfSpace extends Throwable{
	public final NumberSize numberSize;
	public final long       num;
	
	public BitDepthOutOfSpace(NumberSize numberSize, long num){
		super(num+" can't fit in to "+numberSize);
		this.numberSize=numberSize;
		this.num=num;
	}
}
