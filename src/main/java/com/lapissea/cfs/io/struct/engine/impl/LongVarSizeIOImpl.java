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

public class LongVarSizeIOImpl extends VariableNode.PrimitiveLong{
	
	private final Field                valueField;
	private final IOStruct.Get.GetterL getFun;
	private final IOStruct.Set.SetterL setFun;
	private final NumberSize           defaultSize;
	private final Field                varSize;
	
	public LongVarSizeIOImpl(String name, int index, Field valueField, IOStruct.Get.GetterL getFun, IOStruct.Set.SetterL setFun, Field varSize, NumberSize defaultSize){
		super(name, index);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
		this.varSize=varSize;
		this.defaultSize=defaultSize==NumberSize.VOID?null:defaultSize;
	}
	
	private NumberSize getSize(IOInstance source){
		NumberSize size;
		try{
			size=(NumberSize)varSize.get(source);
		}catch(IllegalAccessException e){
			throw new ShouldNeverHappenError(e);
		}
		if(size==null) size=defaultSize;
		if(size==null) throw new NullPointerException(varSize+" must be non null if no default size is provided!");
		if(size==NumberSize.VOID) throw new IllegalStateException("Size can not be VOID!");
		return size;
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
		if(setFun!=null) setFun.setValue(target, newValue);
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
		return getSize(target).read(source);
	}
	
	@Override
	protected void write(IOInstance target, ContentWriter dest, long source) throws IOException{
		getSize(target).write(dest, source);
	}
	
	@Deprecated
	@Override
	public long mapSize(IOInstance target){
		return getSize(target).bytes;
	}
	
	@Override
	public OptionalLong getMaximumSize(){
		return NumberSize.LARGEST.optionalBytesLong;
	}
}
