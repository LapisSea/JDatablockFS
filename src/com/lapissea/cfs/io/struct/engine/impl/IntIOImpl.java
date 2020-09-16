package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.OptionalLong;

public class IntIOImpl extends VariableNode.PrimitiveInt implements VariableNode.FixedSize{
	
	private final Field                valueField;
	private final IOStruct.Get.GetterI getFun;
	private final IOStruct.Set.SetterI setFun;
	private final NumberSize           size;
	
	public IntIOImpl(String name, int index, Field valueField, IOStruct.Get.GetterI getFun, IOStruct.Set.SetterI setFun, NumberSize size){
		super(name, index);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
		this.size=size;
	}
	
	@Override
	protected int get(IOStruct.Instance source){
		if(getFun!=null) return getFun.getValue(source);
		else{
			try{
				return valueField.getInt(source);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	@Override
	protected void set(IOStruct.Instance target, int newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else{
			try{
				valueField.setInt(target, newValue);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	
	@Override
	protected int read(IOStruct.Instance target, ContentReader source, int oldVal) throws IOException{
		return (int)size.read(source);
	}
	
	@Override
	protected void write(IOStruct.Instance target, ContentWriter dest, int source) throws IOException{
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
	public long mapSize(IOStruct.Instance target){
		return getSize();
	}
}
