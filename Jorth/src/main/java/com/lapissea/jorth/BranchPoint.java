package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.IllegalConditionalMerge;
import com.lapissea.jorth.lang.type.GenericType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class BranchPoint{
	
	public record Edge(String name, List<GenericType> stack, boolean reachesDestination){
		public Edge{
			Objects.requireNonNull(name);
			stack = List.copyOf(stack);
		}
		public static Edge capture(String name, CodeBlock block){
			return new Edge(name, block.stackView(), !block.terminates());
		}
	}
	
	public static BranchPoint ofContract(String name, List<GenericType> contract){
		return new BranchPoint(name, contract);
	}
	
	private final String            name;
	private final List<Edge>        ingoing = new ArrayList<>();
	private       List<GenericType> contract;
	
	public BranchPoint(){
		name = "branch merge";
	}
	private BranchPoint(String name, List<GenericType> contract){
		this.name = Objects.requireNonNull(name);
		this.contract = List.copyOf(contract);
	}
	
	public void addIngoing(Edge edge){
		ingoing.add(Objects.requireNonNull(edge));
		if(contract == null && edge.reachesDestination()) contract = edge.stack();
	}
	public void addIngoing(CodeBlock block){
		addIngoing(Edge.capture("incoming edge", block));
	}
	
	public void validateMerge() throws IllegalConditionalMerge{
		for(var edge : ingoing){
			if(!edge.reachesDestination()){
				continue;
			}
			// contract should never be null here
			if(!contract.equals(edge.stack())){
				throw new IllegalConditionalMerge(
					"Stacks must match at " + name + " for " + edge.name() + ":\n" +
					"  expected " + contract + "\n" +
					"  actual   " + edge.stack()
				);
			}
		}
	}
	
	public Optional<List<GenericType>> getOutgoingTypeStack() throws IllegalConditionalMerge{
		validateMerge();
		return ingoing.stream().anyMatch(Edge::reachesDestination)? Optional.of(contract) : Optional.empty();
	}
}
