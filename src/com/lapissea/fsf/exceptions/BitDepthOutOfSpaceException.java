package com.lapissea.fsf.exceptions;

import com.lapissea.fsf.NumberSize;

public class BitDepthOutOfSpaceException extends Throwable{
	public final NumberSize numberSize;
	public final long       num;
	
	public BitDepthOutOfSpaceException(NumberSize numberSize, long num){
		this.numberSize=numberSize;
		this.num=num;
	}
}
