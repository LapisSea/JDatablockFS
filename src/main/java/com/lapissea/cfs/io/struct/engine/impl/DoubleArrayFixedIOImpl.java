package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;
import java.lang.invoke.VarHandle;

public class DoubleArrayFixedIOImpl extends VariableNode.ArrayVar.Fixed<double[]>{
	
	private final VarHandle                     valueField;
	private final IOStruct.Get.Getter<double[]> getFun;
	private final IOStruct.Set.Setter<double[]> setFun;
	private final NumberSize                    elementSize;
	
	public DoubleArrayFixedIOImpl(VarInfo info, int fixedElementSize, VarHandle valueField, NumberSize elementSize, IOStruct.Get.Getter<double[]> getFun, IOStruct.Set.Setter<double[]> setFun){
		super(info, fixedElementSize);
		this.elementSize=elementSize;
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
	}
	
	
	@Override
	protected double[] getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else return (double[])valueField.get(source);
	}
	
	@Override
	protected void setValue(IOInstance target, double[] newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else valueField.set(target, newValue);
	}
	
	@Override
	protected double[] makeArray(int size){
		return new double[size];
	}
	
	
	@Override
	protected void readElements(Cluster cluster, IOInstance target, ContentReader source, double[] array) throws IOException{
		for(int i=0;i<array.length;i++){
			array[i]=Double.longBitsToDouble(elementSize.read(source));
		}
	}
	
	@Override
	protected void writeElements(Cluster cluster, IOInstance target, ContentWriter dest, double[] array) throws IOException{
		for(var l : array){
			elementSize.write(dest, Double.doubleToLongBits(l));
		}
	}
	
	@Override
	protected long getFixedElementsSize(){
		return elementSize.bytes*fixedArraySize;
	}
}
