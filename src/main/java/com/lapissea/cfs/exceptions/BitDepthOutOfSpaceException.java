package com.lapissea.cfs.exceptions;


import com.lapissea.cfs.objects.NumberSize;

public class BitDepthOutOfSpaceException extends Exception{
	public final NumberSize numberSize;
	public final long       num;
	
	public BitDepthOutOfSpaceException(NumberSize numberSize, long num){
		super(num+" can not fit inside "+numberSize);
		this.numberSize=numberSize;
		this.num=num;
		
		assert !numberSize.canFit(num);
	}
}
