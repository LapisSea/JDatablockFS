package com.lapissea.cfs.exceptions;


import com.lapissea.cfs.objects.NumberSize;

import java.io.Serial;

public class BitDepthOutOfSpaceException extends Exception{
	@Serial
	private static final long       serialVersionUID = -4952594787641895300L;
	public final         NumberSize numberSize;
	public final         long       num;
	
	public BitDepthOutOfSpaceException(NumberSize numberSize, long num){
		super(num + " can not fit inside " + numberSize + " unsigned: " + numberSize.maxSize + " signed: " + numberSize.signedMinValue + " - " + numberSize.signedMaxValue);
		this.numberSize = numberSize;
		this.num = num;
		
		assert !numberSize.canFit(num);
	}
}
