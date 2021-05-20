package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct.Get.Getter;
import com.lapissea.cfs.io.struct.IOStruct.Set.Setter;
import com.lapissea.cfs.io.struct.VariableNode;

import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.util.Objects;

public class EnumPaddedDefaultIOImpl<T extends Enum<T>> extends VariableNode.Flag<T>{
	
	private final EnumUniverse<T> flagInfo;
	private final VarHandle       valueField;
	private final Getter<T>       getFun;
	private final Setter<T>       setFun;
	private final boolean         nullable;
	
	public EnumPaddedDefaultIOImpl(VarInfo info, boolean nullable, int totalBits, int paddingBits, EnumUniverse<T> flagInfo, VarHandle valueField, Getter<T> getFun, Setter<T> setFun){
		super(info, totalBits, paddingBits);
		this.nullable=nullable;
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
		this.flagInfo=flagInfo;
	}
	
	@Override
	public T readData(IOInstance target, BitReader source, T oldVal) throws IOException{
		return flagInfo.read(source, nullable);
	}
	
	@Override
	public void writeData(IOInstance target, BitWriter dest, T source) throws IOException{
		if(!nullable) Objects.requireNonNull(source);
		flagInfo.write(source, dest, nullable);
	}
	
	@Override
	@SuppressWarnings("unchecked")
	public T getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else{
				return (T)valueField.get(source);
		}
	}
	
	@Override
	public void setValue(IOInstance target, T value){
		if(!nullable) Objects.requireNonNull(value);
		
		if(setFun!=null) setFun.setValue(target, value);
		else{
			valueField.set(target, value);
		}
	}
}
