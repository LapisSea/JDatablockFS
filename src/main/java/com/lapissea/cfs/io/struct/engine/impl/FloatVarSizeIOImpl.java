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

public class FloatVarSizeIOImpl extends VariableNode.PrimitiveFloat{
	
	private final Field                valueField;
	private final IOStruct.Get.GetterF getFun;
	private final IOStruct.Set.SetterF setFun;
	private final NumberSize           defaultSize;
	private final Field                varSize;
	
	public FloatVarSizeIOImpl(String name, int index, Field valueField, IOStruct.Get.GetterF getFun, IOStruct.Set.SetterF setFun, Field varSize, NumberSize defaultSize){
		super(name, index);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
		this.varSize=varSize;
		this.defaultSize=defaultSize==NumberSize.VOID?null:defaultSize;
		if(defaultSize!=NumberSize.INT)throw new MalformedStructLayout("Float size of "+defaultSize+" not supported");
	}
	
	private NumberSize getSize(IOInstance source){
		NumberSize size;
		try{
			size=(NumberSize)varSize.get(source);
		}catch(IllegalAccessException e){
			throw new ShouldNeverHappenError(e);
		}
		if(size==null) size=defaultSize;
		if(size==null) throw new NullPointerException(source.getClass().getName()+" "+varSize+" must be non null if no default size is provided!");
		if(size==NumberSize.VOID) throw new IllegalStateException(source.getClass().getName()+" Size can not be VOID!");
		
		if(size!=NumberSize.INT)throw new IllegalStateException("Float size of "+size+" not supported");
		return size;
	}
	
	@Override
	protected float get(IOInstance source){
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
	protected void set(IOInstance target, float newValue){
		if(setFun!=null) setFun.setValue(target, newValue);
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
		return Float.intBitsToFloat((int)getSize(target).read(source));
	}
	
	@Override
	protected void write(IOInstance target, ContentWriter dest, float source) throws IOException{
		getSize(target).write(dest, Float.floatToIntBits(source));
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
