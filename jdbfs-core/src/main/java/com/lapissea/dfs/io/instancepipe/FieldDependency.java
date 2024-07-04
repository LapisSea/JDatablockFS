package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.utils.Iters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FieldDependency<T extends IOInstance<T>>{
	
	public record Ticket<T extends IOInstance<T>>(
		boolean fullWrite,
		boolean fullRead,
		FieldSet<T> writeFields,
		FieldSet<T> readFields,
		List<IOField.ValueGeneratorInfo<T, ?>> generators
	){ }
	
	
	private Map<IOField<T, ?>, Ticket<T>> singleDependencyCache = Map.of();
	private Map<FieldSet<T>, Ticket<T>>   multiDependencyCache  = Map.of();
	
	private final FieldSet<T> allFields;
	
	public FieldDependency(FieldSet<T> allFields){
		this.allFields = allFields;
	}
	
	public Ticket<T> getDeps(Set<String> names){
		return getDeps(FieldSet.of(Iters.from(names).map(allFields::requireByName)));
	}
	public Ticket<T> getDeps(FieldSet<T> selectedFields){
		if(selectedFields.isEmpty()) return emptyTicket();
		if(selectedFields.size() == 1){
			return getDeps(selectedFields.getFirst());
		}
		
		var cached = multiDependencyCache.get(selectedFields);
		if(cached != null) return cached;
		
		var tmp   = new HashMap<>(multiDependencyCache);
		var field = generateFieldsDependency(selectedFields);
		tmp.put(selectedFields, field);
		multiDependencyCache = Map.copyOf(tmp);
		return field;
	}
	private Ticket<T> emptyTicket(){
		return new Ticket<T>(false, false, FieldSet.of(), FieldSet.of(), List.of());
	}
	
	public Ticket<T> getDeps(IOField<T, ?> selectedField){
		var cached = singleDependencyCache.get(selectedField);
		if(cached != null) return cached;
		
		var ticket = generateFieldDependency(selectedField);
		
		var tmp = new HashMap<>(singleDependencyCache);
		tmp.put(selectedField, ticket);
		singleDependencyCache = Map.copyOf(tmp);
		
		return ticket;
	}
	
	private Ticket<T> generateFieldsDependency(FieldSet<T> selectedFields){
		if(selectedFields.isEmpty()){
			return emptyTicket();
		}
		selectedFields.forEach(this::checkExistenceOfField);
		if(selectedFields.size() == allFields.size()){
			return new Ticket<>(true, true, allFields, allFields, collectGenerators(allFields));
		}
		var writeFields = new ArrayList<IOField<T, ?>>();
		var readFields  = new ArrayList<IOField<T, ?>>();
		for(IOField<T, ?> selectedField : selectedFields){
			var part = getDeps(selectedField);
			part.writeFields.stream().filter(f -> writeFields.stream().flatMap(IOField::streamUnpackedFields).noneMatch(ef -> ef == f)).forEach(writeFields::add);
			part.readFields.stream().filter(f -> readFields.stream().flatMap(IOField::streamUnpackedFields).noneMatch(ef -> ef == f)).forEach(readFields::add);
		}
		return makeTicket(new HashSet<>(writeFields), new HashSet<>(readFields));
	}
	
	private Ticket<T> generateFieldDependency(IOField<T, ?> selectedField){
		checkExistenceOfField(selectedField);
		
		Set<IOField<T, ?>> selectedWriteFieldsSet = new HashSet<>();
		selectedWriteFieldsSet.add(selectedField);
		Set<IOField<T, ?>> selectedReadFieldsSet = new HashSet<>();
		selectedReadFieldsSet.add(selectedField);
		
		boolean shouldRun = true;
		while(shouldRun){
			shouldRun = false;
			
			for(IOField<T, ?> field : List.copyOf(selectedWriteFieldsSet)){
				if(field.hasDependencies()){
					if(selectedWriteFieldsSet.addAll(allFields.filtered(f -> f.streamUnpackedFields().anyMatch(field::isDependency)).asCollection())){
						shouldRun = true;
					}
				}
				for(var gen : field.getGenerators()){
					if(allFields.contains(gen.field()) && selectedWriteFieldsSet.add(gen.field())){
						shouldRun = true;
					}
				}
			}
			for(IOField<T, ?> field : List.copyOf(selectedReadFieldsSet)){
				if(field.hasDependencies()){
					if(selectedReadFieldsSet.addAll(allFields.filtered(f -> f.streamUnpackedFields().anyMatch(field::isDependency)).asCollection())){
						shouldRun = true;
					}
				}
			}
			
			//Find field and add to read fields when the field is skipped but is a dependency of another
			// skipped field that may need the dependency to correctly skip
			for(IOField<T, ?> field : List.copyOf(selectedReadFieldsSet)){
				var index = allFields.indexOf(field);
				if(index<=0) continue;
				
				var before = new ArrayList<>(allFields.subList(0, index));
				
				before.removeIf(selectedReadFieldsSet::contains);
				
				for(IOField<T, ?> skipped : before){
					//is skipped field dependency of another skipped field who's size may depend on it.
					if(before.stream().filter(e -> !e.getSizeDescriptor().hasFixed())
					         .flatMap(IOField::dependencyStream)
					         .anyMatch(e -> skipped.streamUnpackedFields().anyMatch(s -> s == e))){
						selectedReadFieldsSet.add(skipped);
						shouldRun = true;
					}
				}
				
			}
			
			for(IOField<T, ?> field : List.copyOf(selectedWriteFieldsSet)){
				if(field.getSizeDescriptor().hasFixed()){
					continue;
				}
				
				var index = allFields.indexOf(field);
				if(index == -1) throw new AssertionError();//TODO handle fields in fields
				for(int i = index + 1; i<allFields.size(); i++){
					if(selectedWriteFieldsSet.add(allFields.get(i))){
						shouldRun = true;
					}
				}
			}
		}
		
		return makeTicket(selectedWriteFieldsSet, selectedReadFieldsSet);
	}
	
	private static <T extends IOInstance<T>> List<IOField.ValueGeneratorInfo<T, ?>> collectGenerators(Collection<IOField<T, ?>> writeFields){
		return Utils.nullIfEmpty(IOFieldTools.fieldsToGenerators(List.copyOf(writeFields)));
	}
	
	private FieldSet<T> fieldSetToOrderedList(FieldSet<T> source, Set<IOField<T, ?>> fieldsSet){
		List<IOField<T, ?>> result = new ArrayList<>(fieldsSet.size());
		for(IOField<T, ?> f : source){
			var iter       = f.streamUnpackedFields().iterator();
			var anyRemoved = false;
			while(iter.hasNext()){
				var fi = iter.next();
				if(fieldsSet.remove(fi)) anyRemoved = true;
			}
			
			if(anyRemoved){
				result.add(f);
			}
		}
		if(!fieldsSet.isEmpty()){
			throw new IllegalStateException(fieldsSet + "");
		}
		return FieldSet.of(result);
	}
	
	private Ticket<T> makeTicket(Set<IOField<T, ?>> writeFields, Set<IOField<T, ?>> readFields){
		var w = fieldSetToOrderedList(allFields, writeFields);
		var r = fieldSetToOrderedList(allFields, readFields);
		var g = collectGenerators(writeFields);
		return new Ticket<>(w.equals(allFields), r.equals(allFields), w, r, g);
	}
	
	private void checkExistenceOfField(IOField<T, ?> selectedField){
		if(allFields.contains(selectedField)) return;
		throw new IllegalArgumentException(selectedField + " is not listed!");
	}
}
