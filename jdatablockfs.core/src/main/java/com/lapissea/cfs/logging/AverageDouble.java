package com.lapissea.cfs.logging;

import java.util.function.DoubleConsumer;

public final class AverageDouble implements DoubleConsumer{
	private int    n     =1;
	private double curAvg=0;
	
	@Override
	public void accept(double newNum){
		curAvg=curAvg+(newNum-curAvg)/n;
		n++;
	}
	
	public double getAvg(){
		return curAvg;
	}
	public double getTotal(){
		return curAvg*n;
	}
	public int getCount(){
		return n;
	}
}
