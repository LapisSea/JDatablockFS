package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.IllegalConditionalMerge;
import com.lapissea.jorth.lang.type.GenericType;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JorthBranchPointTests{
	
	@Test
	public void edgeCapturesImmutableStack() throws Exception{
		var source = new ArrayList<>(List.of(GenericType.INT, GenericType.LONG));
		var edge   = new BranchPoint.Edge("first", source, true);
		var point  = new BranchPoint();
		point.addIngoing(edge);
		source.clear();
		assertThat(edge.stack()).containsExactly(GenericType.INT, GenericType.LONG);
		assertThatThrownBy(() -> edge.stack().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThat(point.getOutgoingTypeStack().orElseThrow()).containsExactly(GenericType.INT, GenericType.LONG);
		assertThatThrownBy(() -> point.getOutgoingTypeStack().orElseThrow().clear()).isInstanceOf(UnsupportedOperationException.class);
	}
	
	@Test
	public void matchingVirtualEdgesMergeWithoutCodeBlocks() throws Exception{
		var point = new BranchPoint();
		point.addIngoing(new BranchPoint.Edge("true", List.of(GenericType.INT, GenericType.DOUBLE), true));
		point.addIngoing(new BranchPoint.Edge("false", List.of(GenericType.INT, GenericType.DOUBLE), true));
		point.validateMerge();
		assertThat(point.getOutgoingTypeStack().orElseThrow()).containsExactly(GenericType.INT, GenericType.DOUBLE);
	}
	
	@Test
	public void reachableEmptyStackIsDistinctFromUnreachablePoint() throws Exception{
		var point = new BranchPoint();
		assertThat(point.getOutgoingTypeStack()).isEmpty();
		point.addIngoing(new BranchPoint.Edge("entry", List.of(), true));
		assertThat(point.getOutgoingTypeStack()).isPresent();
		assertThat(point.getOutgoingTypeStack().orElseThrow()).isEmpty();
	}
	
	@Test
	public void mismatchingDepthOrderAndTypesAreRejected(){
		for(var mismatch : List.of(
			List.of(GenericType.INT),
			List.of(GenericType.LONG, GenericType.INT),
			List.of(GenericType.INT, GenericType.DOUBLE)
		)){
			var point = new BranchPoint();
			point.addIngoing(new BranchPoint.Edge("first", List.of(GenericType.INT, GenericType.LONG), true));
			point.addIngoing(new BranchPoint.Edge("second", mismatch, true));
			assertThatThrownBy(point::validateMerge).isInstanceOf(IllegalConditionalMerge.class);
			assertThatThrownBy(point::getOutgoingTypeStack).isInstanceOf(IllegalConditionalMerge.class);
		}
	}
	
	@Test
	public void terminatingEdgesDoNotEstablishOrConstrainContract() throws Exception{
		var point = new BranchPoint();
		point.addIngoing(new BranchPoint.Edge("return", List.of(GenericType.STRING), false));
		point.addIngoing(new BranchPoint.Edge("fallthrough", List.of(GenericType.INT), true));
		point.addIngoing(new BranchPoint.Edge("throw", List.of(GenericType.DOUBLE, GenericType.LONG), false));
		point.validateMerge();
		assertThat(point.getOutgoingTypeStack().orElseThrow()).containsExactly(GenericType.INT);
	}
	
	@Test
	public void allTerminatingEdgesLeavePointUnreachable() throws Exception{
		for(var point : List.of(new BranchPoint(), BranchPoint.ofContract("header", List.of(GenericType.INT)))){
			assertThat(point.getOutgoingTypeStack()).isEmpty();
			point.addIngoing(new BranchPoint.Edge("return", List.of(GenericType.STRING), false));
			point.addIngoing(new BranchPoint.Edge("throw", List.of(GenericType.LONG), false));
			point.validateMerge();
			assertThat(point.getOutgoingTypeStack()).isEmpty();
		}
	}
	
	@Test
	public void fixedHeaderCapturesContractAndAcceptsMatchingBackEdge() throws Exception{
		var contract = new ArrayList<>(List.of(GenericType.LONG));
		var point    = BranchPoint.ofContract("loop header", contract);
		contract.clear();
		point.addIngoing(new BranchPoint.Edge("entry", List.of(GenericType.LONG), true));
		assertThat(point.getOutgoingTypeStack().orElseThrow()).containsExactly(GenericType.LONG);
		point.addIngoing(new BranchPoint.Edge("back edge", List.of(GenericType.LONG), true));
		point.validateMerge();
		assertThat(point.getOutgoingTypeStack().orElseThrow()).containsExactly(GenericType.LONG);
	}
	
	@Test
	public void fixedHeaderRejectsFirstIncomingMismatch(){
		var point = BranchPoint.ofContract("loop header", List.of(GenericType.INT));
		point.addIngoing(new BranchPoint.Edge("entry", List.of(GenericType.LONG), true));
		assertThatThrownBy(point::validateMerge).isInstanceOf(IllegalConditionalMerge.class);
		assertThatThrownBy(point::getOutgoingTypeStack).isInstanceOf(IllegalConditionalMerge.class);
	}
	
	@Test
	public void lateBackEdgeIsValidatedAfterSuccessfulRead() throws Exception{
		var point = BranchPoint.ofContract("loop header", List.of(GenericType.INT));
		point.addIngoing(new BranchPoint.Edge("entry", List.of(GenericType.INT), true));
		point.validateMerge();
		assertThat(point.getOutgoingTypeStack().orElseThrow()).containsExactly(GenericType.INT);
		point.addIngoing(new BranchPoint.Edge("back edge", List.of(GenericType.LONG), true));
		assertThatThrownBy(point::validateMerge).isInstanceOf(IllegalConditionalMerge.class);
		assertThatThrownBy(point::getOutgoingTypeStack).isInstanceOf(IllegalConditionalMerge.class);
	}
}
