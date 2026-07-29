package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.IllegalConditionalMerge;
import com.lapissea.jorth.lang.type.GenericType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class BranchPoint{
	
	private final List<CodeBlock> ingoing  = new ArrayList<>();
	private final List<CodeBlock> outgoing = new ArrayList<>();
	
	public void addIngoing(CodeBlock block){
		Objects.requireNonNull(block);
		ingoing.add(block);
	}
	
	public void addOutgoing(CodeBlock block){
		Objects.requireNonNull(block);
		block.lockEditing();
		outgoing.add(block);
	}
	
	public void validateMerge() throws IllegalConditionalMerge{
		CodeBlock first = null;
		for(CodeBlock block : ingoing){
			if(block.terminates()) continue;
			if(first == null){
				first = block;
				continue;
			}
			if(!first.stacksMatch(block)){
				throw new IllegalConditionalMerge(
					"Stacks must match but there is:\n" +
					ingoing.stream()
					       .filter(e -> !e.terminates())
					       .map(e -> e.stackView().toString())
					       .collect(Collectors.joining("\n", "  ", ""))
				);
			}
		}
	}
	
	public Optional<List<GenericType>> getOutgoingTypeStack(){
		for(CodeBlock block : ingoing){
			if(block.terminates()) continue;
			return Optional.of(block.stackView());
		}
		return Optional.empty();
	}
}
