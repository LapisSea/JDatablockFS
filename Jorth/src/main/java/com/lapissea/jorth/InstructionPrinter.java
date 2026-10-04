package com.lapissea.jorth;

import com.lapissea.jorth.Insn.*;
import com.lapissea.jorth.lang.type.FieldInfo;
import com.lapissea.jorth.lang.type.JType;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.lapissea.jorth.DefinitionPrinter.*;

final class InstructionPrinter{
	
	static void print(Insn instruction, DefinitionPrinter out){
		switch(instruction){
			case IVal i -> op(out, "val", literal(i.val()));
			case LVal i -> op(out, "val", literal(i.val()));
			case FVal i -> op(out, "val", literal(i.val()));
			case DVal i -> op(out, "val", literal(i.val()));
			case BVal i -> op(out, "val", literal(i.val()));
			case StrVal i -> op(out, "val", literal(i.val()));
			case ClassVal i -> op(out, "val", type(i.type()) + "." + keyword("class"));
			case NullConstant ignored -> op(out, "val", keyword("null"));
			case PrimitiveCastOp i -> op(out, "cast", type(i.from()) + " -> " + type(i.to()));
			case CastOp i -> op(out, "cast", type(i.type()));
			case GetLocal i -> op(out, "get", name(i.name()));
			case PutLocalVarOp i -> op(out, "put", name(i.name()));
			case NumericComparison i -> op(out, "compare", (i.greater()? ">" : "<") + (i.orEqual()? "=" : ""));
			case Equality i -> op(out, "compare", i.checkFor()? "==" : "!=");
			case ReturnOp ignored -> op(out, "return", "");
			case ThrowOp ignored -> op(out, "throw", "");
			case PopOp ignored -> op(out, "pop", "");
			case DupOp ignored -> op(out, "dup", "");
			case SwapOp ignored -> op(out, "swap", "");
			case NewOp i -> {
				op(out, "new", type(i.type()));
				if(i.dup()) op(out, "dup", "");
			}
			case PutFieldOp i -> op(out, "put", field(i.field()));
			case GetFieldOp i -> op(out, "get", field(i.field()));
			case PutElementOp i -> op(out, "setElement", type(i.element()));
			case GetElementOp i -> op(out, "getElement", type(i.element()));
			case InvokeOp i -> out.line(callHeader(i) + ";");
			case VirtualCallOp i -> {
				out.open(callHeader(i));
				printBootstrap(i, out);
				out.close();
			}
			case Arithmetic i -> op(out, i.operation().name().toLowerCase(java.util.Locale.ROOT), i.constant() == null? "" : literal(i.constant()));
			case BitShiftLeft ignored -> op(out, "bitShiftLeft", "");
			case BitShiftRight i -> op(out, i.logical()? "unsignedBitShiftRight" : "bitShiftRight", "");
			case BitAnd ignored -> op(out, "bitAnd", "");
			case BitOr ignored -> op(out, "bitOr", "");
			case LoopJump i -> op(out, i.isBreak()? "break" : "continue", "");
			case ConditionalJump i -> {
				var condition = switch(i.type()){
					case TRUE_BOOL -> "ifTrue";
					case EQUALITY -> "ifEqual";
				};
				out.open(keyword(condition));
				if(i.onTrue() != null) i.onTrue().print(out);
				out.close();
				if(i.onFalse() != null){
					out.open(keyword("else"));
					i.onFalse().print(out);
					out.close();
				}
			}
			case WhileLoop i -> {
				out.open(keyword("while"));
				out.open(keyword("check"));
				i.check().print(out);
				out.close();
				out.open(keyword("body"));
				i.body().print(out);
				out.close();
				out.close();
			}
			case InlineBlock i -> {
				out.open(keyword("scope"));
				i.block().print(out);
				out.close();
			}
			case LazyBlock ignored -> out.line(keyword("lazyBlock") + " " + comment("/* deferred */"));
			case TryBlock i -> {
				out.open(keyword("try"));
				i.body.print(out);
				out.close();
				for(var handler : i.handlers){
					out.open(keyword("catch") + " (" + type(handler.type()) +
					         (handler.exception() == null? "" : " " + name(handler.exception().name())) + ")");
					handler.body().print(out);
					out.close();
				}
			}
		}
	}
	
	static String callHeader(InvokeOp call){
		var fn = call.function();
		return keyword(call.superCall()? "superCall" : "call") + " " +
		       (fn.isStatic()? keyword("static") + " " : "") + type(fn.ownerInfo().name()) + "." + name(fn.name()) +
		       args(fn.argumentTypes()) +
		       " -> " + type(fn.returnType());
	}
	
	static String callHeader(VirtualCallOp call){
		return keyword("invokedynamic") + " " +
		       name(call.callingSignature().name()) +
		       args(call.callingSignature().args()) +
		       " -> " + type(call.callingReturnType());
	}
	static void printBootstrap(VirtualCallOp call, DefinitionPrinter out){
		var handle = call.bootstrapHandle();
		var bootstrapArgs = Arrays.stream(Type.getArgumentTypes(handle.getDesc()))
		                          .map(t -> type(t.getClassName()))
		                          .collect(Collectors.joining(", ", "(", ")"));
		
		op(
			out, "bootstrap",
			type(handle.getOwner().replace('/', '.')) +
			"." + name(handle.getName()) +
			bootstrapArgs +
			" -> " + type(Type.getReturnType(handle.getDesc()).getClassName())
		);
		
		for(var arg : call.bootstrapArgs()){
			op(out, "arg", literal(arg));
		}
	}
	
	private static void op(DefinitionPrinter out, String operation, String operands){
		out.line(keyword(operation) + (operands.isEmpty()? "" : " " + operands) + ";");
	}
	private static String field(FieldInfo field){
		return (field.isStatic()? keyword("static") + " " : "") +
		       type(field.owner()) + "." + name(field.name()) +
		       ": " + type(field.type());
	}
	private static String args(List<JType> args){
		return args.stream()
		           .map(DefinitionPrinter::type)
		           .collect(Collectors.joining(", ", "(", ")"));
	}
}
