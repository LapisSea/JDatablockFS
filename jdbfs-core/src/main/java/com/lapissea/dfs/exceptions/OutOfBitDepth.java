package com.lapissea.dfs.exceptions;


import com.lapissea.dfs.objects.NumberSize;

import java.io.Serial;

public class OutOfBitDepth extends Exception{
	@Serial
	private static final long       serialVersionUID = -4952594787641895300L;
	public final         NumberSize numberSize;
	public final         long       num;
	
	private static String makeMsg(NumberSize numberSize, long num, boolean signed){
		var str = num + " can not fit inside " + numberSize + ": ";
		if(signed){
			if(num<0) return str + " min signed value is " + numberSize.signedMinValue;
			return str + "max signed value is " + numberSize.signedMaxValue;
		}else return str + "max value is " + numberSize.maxSize;
	}
	
	public OutOfBitDepth(NumberSize numberSize, long num, boolean signed){
		super(makeMsg(numberSize, num, signed));
		this.numberSize = numberSize;
		this.num = num;
		
		assert !numberSize.canFit(num, signed) :
			num + " can fit in to " + numberSize + ", " +
			(signed? (
				num<0?
				"signedMinValue = " + numberSize.signedMinValue :
				"signedMaxValue = " + numberSize.signedMaxValue
			) : "maxSize = " + numberSize.maxSize);
	}
}
