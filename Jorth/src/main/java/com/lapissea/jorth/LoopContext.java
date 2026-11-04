package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.type.GenericType;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.List;

import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IFEQ;

final class LoopContext{
	private final List<GenericType> entryStack;
	private final BranchPoint       header;
	private final BranchPoint       exit;
	private       Label             headerLabel;
	private       Label             exitLabel;
	
	LoopContext(List<GenericType> entryStack){
		this.entryStack = List.copyOf(entryStack);
		header = BranchPoint.ofContract("loop header", entryStack);
		exit = BranchPoint.ofContract("loop exit", entryStack);
		header.addIngoing(new BranchPoint.Edge("loop entry", entryStack, true));
	}
	
	void addJump(List<GenericType> stack, boolean isBreak) throws MalformedJorth{
		var edge = new BranchPoint.Edge(isBreak? "break" : "continue", stack, true);
		// Validate before recording so a rejected instruction does not poison the destination.
		var contract = BranchPoint.ofContract(isBreak? "loop exit" : "loop header", entryStack);
		contract.addIngoing(edge);
		contract.validateMerge();
		(isBreak? exit : header).addIngoing(edge);
	}
	
	void complete(CodeBlock check, CodeBlock body) throws MalformedJorth{
		var conditionStack = new java.util.ArrayList<>(entryStack);
		conditionStack.add(GenericType.BOOL);
		var conditionEnd = BranchPoint.ofContract("loop condition", conditionStack);
		conditionEnd.addIngoing(BranchPoint.Edge.capture("condition result", check));
		conditionEnd.validateMerge();
		header.addIngoing(BranchPoint.Edge.capture("body back edge", body));
		exit.addIngoing(new BranchPoint.Edge("condition false", entryStack, true));
		header.validateMerge();
		exit.validateMerge();
	}
	
	void emitJump(MethodVisitor writer, boolean isBreak){
		var label = isBreak? exitLabel : headerLabel;
		if(label == null) throw new IllegalStateException("Loop jump emitted outside its loop");
		writer.visitJumpInsn(GOTO, label);
	}
	
	void visit(MethodVisitor writer, CodeBlock check, CodeBlock body) throws MalformedJorth{
		var previousHeader = headerLabel;
		var previousExit   = exitLabel;
		headerLabel = new Label();
		exitLabel = new Label();
		try{
			writer.visitLabel(headerLabel);
			check.visit(writer);
			writer.visitJumpInsn(IFEQ, exitLabel);
			body.visit(writer);
			if(!body.terminates()) writer.visitJumpInsn(GOTO, headerLabel);
			writer.visitLabel(exitLabel);
		}finally{
			headerLabel = previousHeader;
			exitLabel = previousExit;
		}
	}
}
