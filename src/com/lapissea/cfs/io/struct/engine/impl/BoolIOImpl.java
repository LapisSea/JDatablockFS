package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.util.ShouldNeverHappenError;

import java.lang.reflect.Field;

public class BoolIOImpl extends VariableNode.Flag<Boolean>{
	
	private final Field                valueField;
	private final IOStruct.Get.GetterB getFun;
	private final IOStruct.Set.SetterB setFun;
	
	public BoolIOImpl(String name, int index, Field valueField, IOStruct.Get.GetterB getFun, IOStruct.Set.SetterB setFun){
		super(name, index, 1, 0);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
	}
	
	protected boolean getValueB(IOStruct.Instance source){
		if(getFun!=null) return getFun.getValue(source);
		else{
			try{
				return valueField.getBoolean(source);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	protected void setValueB(IOStruct.Instance target, boolean newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else{
			try{
				valueField.set(target, newValue);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	
	protected boolean readDataB(IOStruct.Instance target, FlagReader source, boolean oldVal){
		return source.readBoolBit();
	}
	
	protected void writeDataB(IOStruct.Instance target, FlagWriter dest, boolean source){
		dest.writeBoolBit(source);
	}
	
	@Deprecated
	@Override
	protected Boolean getValue(IOStruct.Instance source){ return getValueB(source); }
	@Deprecated
	@Override
	protected void setValue(IOStruct.Instance target, Boolean newValue){ setValueB(target, newValue); }
	@Deprecated
	@Override
	protected Boolean readData(IOStruct.Instance target, FlagReader source, Boolean oldVal){ return readDataB(target, source, oldVal); }
	@Deprecated
	@Override
	protected void writeData(IOStruct.Instance target, FlagWriter dest, Boolean source){ writeDataB(target, dest, source); }
}
