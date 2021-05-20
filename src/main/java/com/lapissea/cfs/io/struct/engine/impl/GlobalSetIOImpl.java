package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;
import java.lang.invoke.VarHandle;

public class GlobalSetIOImpl<T extends IOInstance> extends VariableNode.FixedSize.Node<T>{
	
	private static final NumberSize INDEX_SIZE=NumberSize.INT;
	
	private final VarHandle                         valueField;
	private final IOStruct.Get.Getter<T>            getFun;
	private final IOStruct.Set.Setter<T>            setFun;
	
	private final IOStruct structType;
	
	
	public GlobalSetIOImpl(VarInfo info, VarHandle valueField, IOStruct.Get.Getter<T> getFun, IOStruct.Set.Setter<T> setFun,	                       IOStruct structType){
		super(info, INDEX_SIZE.bytes);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
		this.structType=structType;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	protected T getValue(IOInstance source){
		if(getFun==null){
			return (T)valueField.get(source);
		}else{
			return getFun.getValue(source);
		}
	}
	
	@Override
	protected void setValue(IOInstance target, T newValue){
		if(setFun==null){
			valueField.set(target, newValue);
		}else{
			setFun.setValue(target, newValue);
		}
	}
	
	@Override
	public T read(IOInstance target, ContentReader source, T oldVal, Cluster cluster) throws IOException{
		int index=(int)INDEX_SIZE.read(source);
		
		if(index==0) return null;
		index--;
		
		IOList<T> list=cluster.getIndexedObjectDB(structType);
		return list.getElement(index);
	}
	
	@Override
	protected void write(IOInstance target, Cluster cluster, ContentWriter dest, T source) throws IOException{
		int index;
		if(source==null){
			index=0;
		}else{
			IOList<T> list=cluster.getIndexedObjectDB(structType);
			
			int listIndex=list.indexOf(source);
			if(listIndex==-1){
				list.addElement(source);
				index=list.indexOf(source);
			}else{
				index=listIndex;
			}
		}
		
		INDEX_SIZE.write(dest, index);
	}
	
}
