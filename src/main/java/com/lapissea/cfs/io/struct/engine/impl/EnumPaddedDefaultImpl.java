package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.io.bit.EnumFlag;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.util.ShouldNeverHappenError;

import java.lang.reflect.Field;
import java.util.Objects;

public class EnumPaddedDefaultImpl<T extends Enum<T>> extends VariableNode.Flag<T>{
	
	private final EnumFlag<T>            flagInfo;
	private final Field                  valueField;
	private final IOStruct.Get.Getter<T> getFun;
	private final IOStruct.Set.Setter<T> setFun;
	
	public EnumPaddedDefaultImpl(int index, int totalBits, int paddingBits, EnumFlag<T> flagInfo, Field valueField, IOStruct.Get.Getter<T> getFun, IOStruct.Set.Setter<T> setFun){
		super(valueField.getName(), index, totalBits, paddingBits);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
		this.flagInfo=flagInfo;
	}
	
	@Override
	public T readData(IOInstance target, FlagReader source, T oldVal){
		return flagInfo.read(source);
	}
	
	@Override
	public void writeData(IOInstance target, FlagWriter dest, T source){
		Objects.requireNonNull(source);
		flagInfo.write(source, dest);
	}
	
	@Override
	@SuppressWarnings("unchecked")
	public T getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else{
			try{
				return (T)valueField.get(source);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	@Override
	public void setValue(IOInstance target, T value){
		if(setFun!=null) setFun.setValue(target, value);
		else{
			try{
				valueField.set(target, value);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
}
