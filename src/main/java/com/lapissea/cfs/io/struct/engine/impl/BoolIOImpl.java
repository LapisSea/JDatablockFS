package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.lang.reflect.Field;

public class BoolIOImpl extends VariableNode.Flag<Boolean>{
	
	private final Field                valueField;
	private final IOStruct.Get.GetterB getFun;
	private final IOStruct.Set.SetterB setFun;
	
	public BoolIOImpl(VarInfo info, Field valueField, IOStruct.Get.GetterB getFun, IOStruct.Set.SetterB setFun){
		super(info, 1, 0);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
	}
	
	protected boolean getValueB(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else{
			try{
				return valueField.getBoolean(source);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	protected void setValueB(IOInstance target, boolean newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else{
			try{
				valueField.set(target, newValue);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	
	protected boolean readDataB(IOInstance target, BitReader source, boolean oldVal) throws IOException{
		return source.readBoolBit();
	}
	
	protected void writeDataB(IOInstance target, BitWriter dest, boolean source) throws IOException{
		dest.writeBoolBit(source);
	}
	
	@Deprecated
	@Override
	protected Boolean getValue(IOInstance source){ return getValueB(source); }
	
	@Deprecated
	@Override
	protected void setValue(IOInstance target, Boolean newValue){ setValueB(target, newValue); }
	
	@Deprecated
	@Override
	protected Boolean readData(IOInstance target, BitReader source, Boolean oldVal) throws IOException{ return readDataB(target, source, oldVal); }
	
	@Deprecated
	@Override
	protected void writeData(IOInstance target, BitWriter dest, Boolean source) throws IOException{ writeDataB(target, dest, source); }
}
