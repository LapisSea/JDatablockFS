package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.OptionalLong;

public class LongIOImpl extends VariableNode.PrimitiveLong implements VariableNode.FixedSize{
	
	private final Field                valueField;
	private final IOStruct.Get.GetterL getFun;
	private final IOStruct.Set.SetterL setFun;
	private final NumberSize           size;
	
	public LongIOImpl(String name, int index, Field valueField, IOStruct.Get.GetterL getFun, IOStruct.Set.SetterL setFun, NumberSize size){
		super(name, index);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
		this.size=size;
	}
	
	@Override
	protected long get(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else{
			try{
				return valueField.getLong(source);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	@Override
	protected void set(IOInstance target, long newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else{
			try{
				valueField.setLong(target, newValue);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	
	@Override
	protected long read(IOInstance target, ContentReader source, long oldVal) throws IOException{
		return size.read(source);
	}
	
	@Override
	protected void write(IOInstance target, ContentWriter dest, long source) throws IOException{
		size.write(dest, source);
	}
	
	@Override
	public OptionalLong getMaximumSize(){
		return OptionalLong.of(getSize());
	}
	@Override
	public long getSize(){
		return size.bytes;
	}
	
	@Deprecated
	@Override
	public long mapSize(IOInstance target){
		return getSize();
	}
}
