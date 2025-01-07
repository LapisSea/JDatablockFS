package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ShouldNeverHappenError;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class FieldDependency<T extends IOInstance<T>>{
	
	public record Ticket<T extends IOInstance<T>>(
		boolean fullWrite,
		boolean fullRead,
		FieldSet<T> writeFields,
		FieldSet<T> readFields,
		List<IOField.ValueGeneratorInfo<T, ?>> generators,
		int hash
	){
		@Override
		public int hashCode(){ return hash; }
	}
	
	
	private Map<IOField<T, ?>, Ticket<T>> singleDependencyCache = Map.of();
	private Map<FieldSet<T>, Ticket<T>>   multiDependencyCache  = Map.of();
	
	private final FieldSet<T> allFields;
	
	public FieldDependency(FieldSet<T> allFields){
		this.allFields = allFields;
	}
	
	public Ticket<T> getDeps(Iterable<String> names){
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
		return new Ticket<T>(false, false, FieldSet.of(), FieldSet.of(), List.of(), -1);
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
			return new Ticket<>(true, true, allFields, allFields, collectGenerators(allFields), allFields.hashCode());
		}
		var writeFields = new ArrayList<IOField<T, ?>>();
		var readFields  = new ArrayList<IOField<T, ?>>();
		for(IOField<T, ?> selectedField : selectedFields){
			var part = getDeps(selectedField);
			part.writeFields.filtered(f -> Iters.from(writeFields).flatMap(IOField::iterUnpackedFields).noneIs(f)).forEach(writeFields::add);
			part.readFields.filtered(f -> Iters.from(readFields).flatMap(IOField::iterUnpackedFields).noneIs(f)).forEach(readFields::add);
		}
		return makeTicket(new HashSet<>(writeFields), new HashSet<>(readFields));
	}
	
	private void writeDependencies(IOField<T, ?> field, Predicate<IOField<T, ?>> isListed, Consumer<IOField<T, ?>> deps){
		
		if(field.hasDependencies()){
			// add fields that are a dependency or contain any dependencies
			allFields.filtered(f -> f.iterUnpackedFields().anyMatch(field::isDependency)).forEach(deps);
		}
		
		//If a field generates another one, then that one may be changed as well and must be written to preserve consistancy
		Iters.from(field.getGenerators()).map(IOField.ValueGeneratorInfo::field).filter(allFields::contains).forEach(deps);
		
		// must write all fields after this one as it may change size and shift around fields after it
		if(!field.getSizeDescriptor().hasFixed()){
			var index = allFields.indexOf(field);
			if(index == -1) throw new ShouldNeverHappenError();
			
			allFields.subList(index + 1, allFields.size()).forEach(deps);
		}
	}
	private void readDependencies(IOField<T, ?> field, Predicate<IOField<T, ?>> isListed, Consumer<IOField<T, ?>> deps){
		
		if(field.hasDependencies()){
			// add fields that are a dependency or contain any dependencies
			allFields.filtered(f -> f.iterUnpackedFields().anyMatch(field::isDependency)).forEach(deps);
		}
		
		//Find field and add to read fields when the field is skipped but is a dependency of another
		// skipped field that may need the dependency to correctly skip
		var index = allFields.indexOf(field);
		if(index>0){
			var skipped = Iters.from(allFields.subList(0, index)).filterNot(isListed).bake();
			
			var dependenciesOfSkipped = skipped.filter(e -> !e.getSizeDescriptor().hasFixed())
			                                   .flatMap(IOField::getDependencies).bake();
			
			if(dependenciesOfSkipped.hasAny()){
				for(IOField<T, ?> skippedField : skipped){
					//is skipped field dependency of another skipped field who's size may depend on it.
					if(dependenciesOfSkipped.anyMatch(e -> skippedField.iterUnpackedFields().anyIs(e))){
						deps.accept(skippedField);
					}
				}
			}
		}
	}
	
	private Ticket<T> generateFieldDependency(IOField<T, ?> selectedField){
		checkExistenceOfField(selectedField);
		
		var writeFields = fieldBFSResolve(selectedField, this::writeDependencies);
		var readFields  = fieldBFSResolve(selectedField, this::readDependencies);
		
		return makeTicket(writeFields, readFields);
	}
	
	private interface BFSResolve<T extends IOInstance<T>, F extends IOField<T, ?>>{
		void accept(F field, Predicate<F> existing, Consumer<F> add);
	}
	private Set<IOField<T, ?>> fieldBFSResolve(IOField<T, ?> selectedField, BFSResolve<T, IOField<T, ?>> resolve){
		return fieldBFSResolve(List.of(selectedField), resolve);
	}
	private Set<IOField<T, ?>> fieldBFSResolve(Iterable<IOField<T, ?>> selectedFields, BFSResolve<T, IOField<T, ?>> resolve){
		var fields = new HashSet<IOField<T, ?>>(allFields.size());
		var queue  = new ArrayDeque<IOField<T, ?>>();
		
		Consumer<IOField<T, ?>> add = f -> {
			if(fields.add(f)){
				queue.add(f);
			}
		};
		selectedFields.forEach(add);
		
		while(!queue.isEmpty()){
			IOField<T, ?> field;
			while((field = queue.poll()) != null){
				
				resolve.accept(field, fields::contains, add);
				
				if(fields.size() == allFields.size() && fields.containsAll(allFields)){
					return fields;
				}
			}
		}
		
		return fields;
	}
	
	private static <T extends IOInstance<T>> List<IOField.ValueGeneratorInfo<T, ?>> collectGenerators(Collection<IOField<T, ?>> writeFields){
		return Utils.nullIfEmpty(IOFieldTools.fieldsToGenerators(List.copyOf(writeFields)));
	}
	
	private FieldSet<T> fieldSetToOrderedList(FieldSet<T> source, Set<IOField<T, ?>> fieldsSet){
		List<IOField<T, ?>> result = new ArrayList<>(fieldsSet.size());
		for(IOField<T, ?> f : source){
			var anyRemoved = false;
			for(IOField<T, ?> fi : f.iterUnpackedFields()){
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
		var writes     = fieldSetToOrderedList(allFields, writeFields);
		var reads      = fieldSetToOrderedList(allFields, readFields);
		var generators = collectGenerators(writes);
		return new Ticket<>(writes.equals(allFields), reads.equals(allFields), writes, reads, generators, writes.hashCode()^reads.hashCode());
	}
	
	private void checkExistenceOfField(IOField<T, ?> selectedField){
		if(allFields.contains(selectedField)) return;
		throw new IllegalArgumentException(selectedField + " is not listed!");
	}
}
