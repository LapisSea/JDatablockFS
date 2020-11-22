package com.lapissea.cfs.io.struct.engine;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.IOStruct.Get.Getter;
import com.lapissea.cfs.io.struct.IOStruct.Read.Reader;
import com.lapissea.cfs.io.struct.IOStruct.Set.Setter;
import com.lapissea.cfs.io.struct.IOStruct.Write.Writer;
import com.lapissea.cfs.io.struct.StructImpl;
import com.lapissea.cfs.io.struct.ValueRelations;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.io.struct.engine.impl.EnumCustomByteWiseIO;
import com.lapissea.cfs.io.struct.engine.impl.EnumPaddedDefaultImpl;
import com.lapissea.cfs.objects.NumberSize;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Type;

public class EnumNodeMaker<T extends Enum<T>> extends StructReflectionImpl.NodeMaker{
	
	@Override
	protected VariableNode<?> makeNode(IOStruct clazz, String name, ValueRelations.ValueInfo info){
		
		IOStruct.EnumValue valueAnn;
		Field              valueField;
		{
			StructImpl.Annotated<Field, ?> val=info.value();
			
			valueAnn=(IOStruct.EnumValue)val.annotation();
			
			valueField=val.val();
			valueField.setAccessible(true);
		}
		
		VarHandle       varh    =Utils.makeVarHandle(valueField);
		EnumUniverse<T> flagInfo=EnumUniverse.getUnknown(valueField.getType());
		
		int bitSize=flagInfo.getBitSize(valueAnn.nullable());
		
		int paddingBits=switch(valueAnn.customBitSize()){
			case -1 -> 0;
			default -> {
				if(valueAnn.customBitSize()<bitSize){
					throw new MalformedStructLayout(valueField+" has too small custom bit size of "+valueAnn.customBitSize()+" and must be at least "+bitSize);
				}
				yield valueAnn.customBitSize()-bitSize;
			}
		};
		
		int totalBits=bitSize+paddingBits;
		
		VariableNode.VarInfo vInfo=new VariableNode.VarInfo(valueField.getName(), valueAnn.index());
		
		Type      type    =valueField.getGenericType();
		Getter<T> getFun  =Getter.get(info, type);
		Setter<T> setFun  =Setter.get(info, type);
		Reader<T> readFun =Reader.get(info, type);
		Writer<T> writeFun=Writer.get(info, type);
		
		if(readFun!=null||writeFun!=null){
			NumberSize numberSize=NumberSize.byBits(totalBits);
			return new EnumCustomByteWiseIO<>(vInfo, valueAnn.nullable(), numberSize.bytes, varh, flagInfo, numberSize, getFun, setFun, readFun, writeFun);
		}
		
		return new EnumPaddedDefaultImpl<>(vInfo, valueAnn.nullable(), bitSize, paddingBits, flagInfo, varh, getFun, setFun);
	}
}
