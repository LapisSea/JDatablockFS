package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
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

public class FloatIOImpl extends VariableNode.PrimitiveFloat implements VariableNode.FixedSize{
	
	private final Field                valueField;
	private final IOStruct.Get.GetterF getFun;
	private final IOStruct.Set.SetterF setFun;
	private final NumberSize           size;
	
	public FloatIOImpl(String name, int index, Field valueField, IOStruct.Get.GetterF getFun, IOStruct.Set.SetterF setFun, NumberSize size){
		super(name, index);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
		this.size=size;
		if(size!=NumberSize.INT)throw new MalformedStructLayout("Float size of "+size+" not supported");
	}
	
	@Override
	protected float get(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else{
			try{
				return valueField.getFloat(source);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	@Override
	protected void set(IOInstance target, float newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else{
			try{
				valueField.setFloat(target, newValue);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	@Override
	protected float read(IOInstance target, ContentReader source, float oldVal) throws IOException{
		return Float.intBitsToFloat((int)size.read(source));
	}
	
	@Override
	protected void write(IOInstance target, ContentWriter dest, float source) throws IOException{
		size.write(dest, Float.floatToIntBits(source));
	}
	
	@Override
	public final OptionalLong getMaximumSize(){
		return size.optionalBytesLong;
	}
	
	@Override
	public final long getSize(){
		return size.bytes;
	}
	
	@Deprecated
	@Override
	public long mapSize(IOInstance target){
		return getSize();
	}
}
