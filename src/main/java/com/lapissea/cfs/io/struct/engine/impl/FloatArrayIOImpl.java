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

public class FloatArrayIOImpl extends VariableNode.ArrayVar<float[]>{
	
	private final VarHandle                    valueField;
	private final IOStruct.Get.Getter<float[]> getFun;
	private final IOStruct.Set.Setter<float[]> setFun;
	private final NumberSize                   elementSize;
	
	public FloatArrayIOImpl(VarInfo info, VarHandle valueField, NumberSize elementSize, IOStruct.Get.Getter<float[]> getFun, IOStruct.Set.Setter<float[]> setFun){
		super(info, -1);
		this.elementSize=elementSize;
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
	}
	
	
	@Override
	protected float[] getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else return (float[])valueField.get(source);
	}
	
	@Override
	protected void setValue(IOInstance target, float[] newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else valueField.set(target, newValue);
	}
	
	@Override
	protected float[] makeArray(int size){
		return new float[size];
	}
	
	@Override
	protected long getElementsSize(IOInstance target, float[] value){
		return elementSize.bytes*value.length;
	}
	
	@Override
	protected void readElements(Cluster cluster, IOInstance target, ContentReader source, float[] array) throws IOException{
		for(int i=0;i<array.length;i++){
			array[i]=Float.intBitsToFloat((int)elementSize.read(source));
		}
	}
	
	@Override
	protected void writeElements(Cluster cluster, IOInstance target, ContentWriter dest, float[] array) throws IOException{
		for(var l : array){
			elementSize.write(dest, Float.floatToIntBits(l));
		}
	}
	
	
}
