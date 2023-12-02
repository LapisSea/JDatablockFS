package com.lapissea.dfs.core.versioning;

import com.lapissea.dfs.type.IOTypeDB;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.TypeDef;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.OptionalPP;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

public record ClassVersionDiff(Class<?> real, TypeDef stored, List<String> newFields, List<String> removedFields, List<String> changedFields){
	
	public static Optional<ClassVersionDiff> of(String className, IOTypeDB db){
		Class<?> real;
		try{
			real = Class.forName(className);
		}catch(ClassNotFoundException e){
			return Optional.empty();
		}
		
		var     struct = Struct.ofUnknown(real);
		TypeDef stored;
		try{
			stored = db.getDefinitionFromClassName(className).orElseThrow();
		}catch(IOException e){
			throw new UncheckedIOException(e);
		}
		
		var storedF = stored.getFields();
		var realF   = struct.getFields();
		
		
		
		var newF = realF.stream().map(IOField::getName)
		                .filter(name -> storedF.stream().noneMatch(f -> f.getName().equals(name))).toList();
		var removedF = storedF.stream().map(TypeDef.FieldDef::getName)
		                      .filter(name -> realF.byName(name).isEmpty()).toList();
		
		record FPair(IOField<?, ?> real, TypeDef.FieldDef stored){ }
		var changedF =
			storedF
				.stream()
				.map(f -> realF.byName(f.getName()).map(r -> new FPair(r, f)))
				.flatMap(OptionalPP::stream)
				.filter(pair -> {
					var realDef = new TypeDef.FieldDef(pair.real);
					return !pair.stored.equals(realDef);
				})
				.map(p -> p.real.getName())
				.toList();
		
		if(newF.isEmpty() && removedF.isEmpty() && changedF.isEmpty()){
			return Optional.empty();
		}
		
		return Optional.of(new ClassVersionDiff(real, stored, newF, removedF, changedF));
	}
	
}
