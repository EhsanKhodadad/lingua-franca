
package org.lflang.analyses.dag;

import java.util.ArrayList;
import java.util.stream.Collectors;

import org.lflang.TimeUnit;
import org.lflang.TimeValue;
import org.lflang.analyses.statespace.StateSpaceDiagram;
import org.lflang.analyses.statespace.StateSpaceNode;
import org.lflang.generator.CodeBuilder;
import org.lflang.generator.ReactionInstance;
import org.lflang.generator.ReactorInstance;
import org.lflang.generator.c.CFileConfig;

/**
 * Constructs a Directed Acyclic Graph (Dag) from the State Space Diagram.
 * This is part of the static schedule generation.
 *
 * @author Chadlia Jerad
 * @author Shaokai Lin
 */
public class DagGenerator {
    /** The main reactor instance. */
    public ReactorInstance main;

    /** The Dag to be contructed. */
    public Dag dag;

    /**
     * State Space Diagram, to be constructed by explorer() method in 
     * StateSpaceExplorer.
     */
    public StateSpaceDiagram stateSpaceDiagram;

    /** File config */
    protected final CFileConfig fileConfig;

    /**
     * A dot file that represents the diagram
     */
    private CodeBuilder dot;

    /**
     * Constructor. Sets the amin reactor and initializes the dag
     * @param main main reactor instance
     */    
    public DagGenerator(
        CFileConfig fileConfig,
        ReactorInstance main,
        StateSpaceDiagram stateSpaceDiagram
    ) {
        this.fileConfig = fileConfig;
        this.main = main;
        this.stateSpaceDiagram = stateSpaceDiagram;
        this.dag = new Dag();
    }

    /**
     * Generates the Dag.
     * It starts by calling StateSpaceExplorer to construct the state space
     * diagram. This latter, together with the lf program topology and priorities
     * are used to generate the Dag.
     */
    public void generateDag(){
        // Variables
        StateSpaceNode currentStateSpaceNode = this.stateSpaceDiagram.head;
        TimeValue previousTime = TimeValue.ZERO;
        DagNode previousSync = null;
        int loopNodeReached = 0;
        boolean lastIteration = false;

        ArrayList<DagNode> currentReactionNodes                 = new ArrayList<>();
        ArrayList<DagNode> reactionsUnconnectedToSync           = new ArrayList<>();
        ArrayList<DagNode> reactionsUnconnectedToNextInvocation = new ArrayList<>();

        while (currentStateSpaceNode != null) {
            // Check if the current node is a loop node.
            // The stop condition is when the loop node is encountered the 2nd time.
            if (currentStateSpaceNode == this.stateSpaceDiagram.loopNode) {
                loopNodeReached++;
                if (loopNodeReached >= 2)
                    lastIteration = true;
            }

            // Get the current logical time. Or, if this is the last iteration,
            // set the loop period as the logical time.
            TimeValue time;
            if (!lastIteration)
                time = currentStateSpaceNode.time;
            else
                time = new TimeValue(this.stateSpaceDiagram.loopPeriod, TimeUnit.NANO);

            // Add a SYNC node.
            DagNode sync = this.dag.addNode(dagNodeType.SYNC, time);

            // Create DUMMY and Connect SYNC and previous SYNC to DUMMY
            if (! time.equals(TimeValue.ZERO)) {
                TimeValue timeDiff = time.sub(previousTime);
                DagNode dummy = this.dag.addNode(dagNodeType.DUMMY, timeDiff);
                this.dag.addEdge(previousSync, dummy);
                this.dag.addEdge(dummy, sync);
            }

            // Do not add more reaction nodes, and add edges 
            // from existing reactions to the last node.
            if (lastIteration) {
                for (DagNode n : reactionsUnconnectedToSync) {
                    this.dag.addEdge(n, sync);
                }
                break;
            }

            // Add reaction nodes, as well as the edges connecting them to SYNC.
            currentReactionNodes.clear();
            for (ReactionInstance reaction : currentStateSpaceNode.reactionsInvoked) {
                DagNode node = this.dag.addNode(dagNodeType.REACTION, reaction);
                currentReactionNodes.add(node);
                // reactionsUnconnectedToSync.add(node);
                // reactionsUnconnectedToNextInvocation.add(node);
                this.dag.addEdge(sync, node);
            }

            // Now add edges based on reaction dependencies.
            for (DagNode n1 : currentReactionNodes) {
                for (DagNode n2 : currentReactionNodes) {
                    if (n1.nodeReaction
                            .dependentReactions()
                            .contains(n2.nodeReaction)) {
                        this.dag.addEdge(n1, n2);
                    }
                }
            }
            
            // Create a list of ReactionInstances from currentReactionNodes.
            ArrayList<ReactionInstance> currentReactions = 
                currentReactionNodes.stream()
                .map(DagNode::getReaction)
                .collect(Collectors.toCollection(ArrayList::new));
            
            // If there is a newly released reaction found and its prior
            // invocation is not connected to a downstream SYNC node,
            // connect it to a downstream SYNC node to
            // preserve a deterministic order. In other words,
            // check if there are invocations of the same reaction across two
            // time steps, if so, connect the previous invocation to the current
            // SYNC node.
            //
            // FIXME: This assumes that the (conventional) deadline is the
            // period. We need to find a way to integrate LF deadlines into
            // the picture.
            ArrayList<DagNode> toRemove = new ArrayList<>();
            for (DagNode n : reactionsUnconnectedToSync) {
                if (currentReactions.contains(n.nodeReaction)) {
                    this.dag.addEdge(n, sync);
                    toRemove.add(n);
                }
            }
            reactionsUnconnectedToSync.removeAll(toRemove);
            reactionsUnconnectedToSync.addAll(currentReactionNodes);

            // Check if there are invocations of reactions from the same reactor
            // across two time steps. If so, connect invocations from the
            // previous time step to those in the current time step, in order to
            // preserve determinism.
            ArrayList<DagNode> toRemove2 = new ArrayList<>();
            for (DagNode n1 : reactionsUnconnectedToNextInvocation) {
                for (DagNode n2 : currentReactionNodes) {
                    ReactorInstance r1 = n1.getReaction().getParent();
                    ReactorInstance r2 = n2.getReaction().getParent();
                    if (r1.equals(r2)) {
                        this.dag.addEdge(n1, n2);
                        toRemove2.add(n1);
                    }
                }
            }
            reactionsUnconnectedToNextInvocation.removeAll(toRemove2);
            reactionsUnconnectedToNextInvocation.addAll(currentReactionNodes);

            // Move to the next state space node.          
            currentStateSpaceNode =
                stateSpaceDiagram.getDownstreamNode(currentStateSpaceNode);
            previousSync = sync;
            previousTime = time;
        }        
    }

    // A getter for the DAG
    public Dag getDag() {
        return this.dag;
    }

    /**
     * Generate a dot file from the state space diagram.
     * 
     * @return a CodeBuilder with the generated code
     */
    public CodeBuilder generateDot() {
        if (dot == null) {
            dot = new CodeBuilder();
            dot.pr("digraph DAG {");
            dot.indent();
            
            // Graph settings
            dot.pr("fontname=\"Calibri\";");
            dot.pr("rankdir=TB;");
            dot.pr("node [shape = circle, width = 2.5, height = 2.5, fixedsize = true];");
            dot.pr("ranksep=2.0;  // Increase distance between ranks");
            dot.pr("nodesep=2.0;  // Increase distance between nodes in the same rank");
            
            // Define nodes.
            ArrayList<Integer> auxiliaryNodes = new ArrayList<>();
            for (int i = 0; i < this.dag.dagNodes.size(); i++) {
                DagNode node = this.dag.dagNodes.get(i);
                String code = "";
                String label = "";
                if (node.nodeType == dagNodeType.SYNC) {
                    label = "label=\"Sync" + "@" + node.timeStep + "\", style=\"dotted\"";
                    auxiliaryNodes.add(i);
                } else if (node.nodeType == dagNodeType.DUMMY) {
                    label = "label=\"Dummy" + "=" + node.timeStep + "\", style=\"dotted\"";
                    auxiliaryNodes.add(i);
                } else if (node.nodeType == dagNodeType.REACTION) {
                    label = "label=\"" + node.nodeReaction.getFullName() + "\nWCET=?ms\"";
                } else {
                    // Raise exception.
                    System.out.println("UNREACHABLE");
                    System.exit(1);
                }
                code += i + "[" + label + "]";
                dot.pr(code);
            }

            // Align auxiliary nodes.
            dot.pr("{");
            dot.indent();
            dot.pr("rank = same;");
            for (Integer i : auxiliaryNodes) {
                dot.pr(i + "; ");
            }
            dot.unindent();
            dot.pr("}");

            // Add edges
            for (DagEdge e : this.dag.dagEdges) {
                int sourceIdx = this.dag.dagNodes.indexOf(e.sourceNode);
                int sinkIdx   = this.dag.dagNodes.indexOf(e.sinkNode);
                dot.pr(sourceIdx + " -> " + sinkIdx);
            }

            dot.unindent();
            dot.pr("}");
        }
        return this.dot;
    }
}