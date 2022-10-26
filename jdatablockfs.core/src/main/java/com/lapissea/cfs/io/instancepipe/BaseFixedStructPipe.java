package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.exceptions.FixedFormatNotSupportedException;
import com.lapissea.cfs.exceptions.IllegalField;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.exceptions.UnsupportedStructLayout;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Predicate.not;

public abstract class BaseFixedStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public BaseFixedStructPipe(Struct<T> type, PipeFieldCompiler<T> compiler, boolean initNow){
		super(type, compiler, initNow);
	}
	
	
	protected static <T extends IOInstance<T>> List<IOField<T, ?>> fixedFieldsSet(Struct<T> type, FieldSet<T> structFields, Set<IOField<T, NumberSize>> sizeFields){
		type.waitForState(Struct.STATE_INIT_FIELDS);
		try{
			return IOFieldTools.stepFinal(
				structFields,
				List.of(
					IOFieldTools.streamStep(s->s.map(f->sizeFields.contains(f)?f:f.forceMaxAsFixedSize())),
					IOFieldTools::dependencyReorder,
					IOFieldTools.streamStep(s->s.filter(not(sizeFields::contains))),
					IOFieldTools::mergeBitSpace
				));
		}catch(FixedFormatNotSupportedException e){
			throw new UnsupportedStructLayout(type.getType().getName()+" does not support fixed size layout because of "+e.getField(), e);
		}
	}
	
	protected Map<IOField<T, NumberSize>, NumberSize> computeMaxValues(FieldSet<T> structFields){
		var badFields=sizeFieldStream(structFields).filter(IOField::hasDependencies).map(IOField::toString).collect(Collectors.joining(", "));
		if(!badFields.isEmpty()){
			throw new IllegalField(badFields+" should not have dependencies");
		}
		
		return sizeFieldStream(structFields)
			       .map(sizingField->{
				       var size=getType().getFields().streamDependentOn(sizingField)
				                         .mapToLong(v->{
					                         v.declaringStruct().waitForState(Struct.STATE_INIT_FIELDS);
					                         return v.getSizeDescriptor().requireMax(WordSpace.BYTE);
				                         })
				                         .distinct()
				                         .mapToObj(l->NumberSize.FLAG_INFO.stream()
				                                                          .filter(s->s.bytes==l)
				                                                          .findAny().orElseThrow())
				                         .reduce((a, b)->{
					                         if(a!=b){
						                         throw new MalformedStruct("inconsistent dependency sizes"+sizingField);
					                         }
					                         return a;
				                         })
				                         .orElse(NumberSize.LARGEST);
				       return new AbstractMap.SimpleEntry<>(sizingField, size);
			       })
			       .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}
	
	protected static <T extends IOInstance<T>> Stream<IOField<T, NumberSize>> sizeFieldStream(FieldSet<T> structFields){
		return structFields.stream().map(f->IOFieldTools.getDynamicSize(f.getAccessor())).filter(Optional::isPresent).map(Optional::get);
	}
	
}
