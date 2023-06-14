package org.lflang.analyses.evm;

import org.lflang.generator.ReactionInstance;

public class InstructionEIT implements Instruction {
    
    /** Opcode of this instruction */
    final private Opcode opcode = Opcode.EIT;

    /** Reaction to be executed */
    public ReactionInstance reaction;

    /** Constructor */
    public InstructionEIT(ReactionInstance reaction) {
        this.reaction = reaction;
    }

	@Override
	public Opcode getOpcode() {
		return this.opcode;
	}

    @Override
    public String toString() {
        return opcode + ": " + this.reaction;
    }
}
