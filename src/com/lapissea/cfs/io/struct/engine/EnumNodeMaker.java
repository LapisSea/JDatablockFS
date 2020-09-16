package com.lapissea.cfs.io.struct.engine;

import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.io.bit.EnumFlag;
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

import java.lang.reflect.Field;

public class EnumNodeMaker<T extends Enum<T>> extends StructReflectionImpl.NodeMaker<T>{
	
	@Override
	protected VariableNode<T> makeNode(Class<?> clazz, String name, ValueRelations.ValueInfo info){
		
		IOStruct.EnumValue valueAnn;
		Field              valueField;
		{
			StructImpl.Annotated<Field, ?> val=info.value();
			
			valueAnn=(IOStruct.EnumValue)val.annotation();
			
			valueField=val.val();
			valueField.setAccessible(true);
		}
		
		
		@SuppressWarnings("unchecked")
		var enumType=(Class<T>)valueField.getType();
		if(!enumType.isEnum()) throw new IllegalArgumentException(enumType.getName()+" is not an Enum");
		
		var flagInfo=EnumFlag.get(enumType);
		
		int paddingBits=switch(valueAnn.customBitSize()){
			case -1 -> 0;
			default -> {
				if(valueAnn.customBitSize()<flagInfo.bits){
					throw new IllegalStateException(valueField+" has too small custom bit size of "+valueAnn.customBitSize());
				}
				yield valueAnn.customBitSize()-flagInfo.bits;
			}
		};
		
		int totalBits=flagInfo.bits+paddingBits;
		
		Getter<T> getFun  =Getter.get(info, enumType);
		Setter<T> setFun  =Setter.get(info, enumType);
		Reader<T> readFun =Reader.get(info, enumType);
		Writer<T> writeFun=Writer.get(info, enumType);
		
		if(readFun!=null||writeFun!=null){
			NumberSize numberSize=NumberSize.byBits(totalBits);
			return new EnumCustomByteWiseIO<>(valueField.getName(), valueAnn.index(), numberSize.bytes, valueField, flagInfo, numberSize, getFun, setFun, readFun, writeFun);
		}
		
		return new EnumPaddedDefaultImpl<>(valueAnn.index(), flagInfo.bits, paddingBits, flagInfo, valueField, getFun, setFun);
	}
}
