package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.type.BaseType;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.TypeStack;
import com.lapissea.util.ShouldNeverHappenError;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.POP2;

public sealed interface Insn{
	
	record GetLocal(GenericType type, String name, int index) implements Insn{
		@Override
		public void simulate(TypeSource typeSource, TypeStack localStack){
			localStack.push(type);
		}
		@Override
		public void visit(MethodVisitor fn){
			fn.visitVarInsn(type.getBaseType().loadOp, index);
		}
	}
	
	record Equality() implements Insn{
		@Override
		public void simulate(TypeSource typeSource, TypeStack stack) throws MalformedJorth{
			
			stack.requireElements(2);
			GenericType a = stack.pop();
			GenericType b = stack.pop();
			
			if(!a.instanceOf(typeSource, b) && !b.instanceOf(typeSource, a)){
				throw new MalformedJorth(a + " not compatible with " + b);
			}
			
			stack.push(GenericType.BOOL);
		}
		@Override
		public void visit(MethodVisitor fn){
		
		}
	}
	
	record ReturnOp(BaseType typ) implements Insn{
		
		static ReturnOp make(JType returnType, TypeSource typeSource, TypeStack localStack) throws MalformedJorth{
			
			if(returnType != null){
				var ret    = returnType.asGeneric();
				var popped = localStack.peekLast();
				if(!popped.instanceOf(typeSource, ret)){
					throw new MalformedJorth("Method returns " + ret + " but " + popped + " is on stack");
				}
				return new ReturnOp(popped.getBaseType());
			}else{
				if(!localStack.isEmpty()){
					throw new MalformedJorth("Returning nothing (void) but there are values " + localStack + " on the stack");
				}
				return new ReturnOp(BaseType.VOID);
			}
		}
		
		@Override
		public void simulate(TypeSource typeSource, TypeStack localStack) throws MalformedJorth{
			localStack.pop();
		}
		
		@Override
		public void visit(MethodVisitor writer){
			writer.visitInsn(typ.returnOp);
		}
	}
	
	record PopOp(int slots) implements Insn{
		
		static PopOp make(TypeStack localStack) throws MalformedJorth{
			var bt = localStack.peekLast().getBaseType();
			return new PopOp(bt.slots);
		}
		
		@Override
		public void simulate(TypeSource typeSource, TypeStack localStack) throws MalformedJorth{
			localStack.pop();
		}
		
		@Override
		public void visit(MethodVisitor writer){
			writer.visitInsn(switch(slots){
				case 1 -> POP;
				case 2 -> POP2;
				default -> throw new ShouldNeverHappenError();
			});
		}
	}
	
	void simulate(TypeSource typeSource, TypeStack localStack) throws MalformedJorth;
	void visit(MethodVisitor fn);
}
