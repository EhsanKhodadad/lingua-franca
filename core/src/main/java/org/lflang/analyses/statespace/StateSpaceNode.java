/**
 * A node in the state space diagram representing a step in the execution of an LF program.
 *
 * @author{Shaokai Lin <shaokai@berkeley.edu>}
 */
package org.lflang.analyses.statespace;

import java.util.ArrayList;
import java.util.Set;
import org.lflang.TimeValue;
import org.lflang.generator.ReactionInstance;
import org.lflang.generator.TriggerInstance;

public class StateSpaceNode {

  public int index; // Set in StateSpaceDiagram.java
  public Tag tag;
  public TimeValue time; // Readable representation of tag.timestamp
  public Set<ReactionInstance> reactionsInvoked;
  public ArrayList<Event> eventQ;

  public StateSpaceNode(Tag tag, Set<ReactionInstance> reactionsInvoked, ArrayList<Event> eventQ) {
    this.tag = tag;
    this.eventQ = eventQ;
    this.reactionsInvoked = reactionsInvoked;
    this.time = TimeValue.fromNanoSeconds(tag.timestamp);
  }

  /**
   * Assuming both eventQs have the same length, for each pair of events in eventQ1 and eventQ2,
   * check if the time distances between the node's tag and the two events' tags are equal.
   */
  private boolean equidistant(StateSpaceNode n1, StateSpaceNode n2) {
    if (n1.eventQ.size() != n2.eventQ.size()) return false;
    for (int i = 0; i < n1.eventQ.size(); i++) {
      if (n1.eventQ.get(i).tag.timestamp - n1.tag.timestamp
          != n2.eventQ.get(i).tag.timestamp - n2.tag.timestamp) {
        return false;
      }
    }
    return true;
  }

  /** Two methods for pretty printing */
  public void display() {
    System.out.println(this);
  }

  public String toString() {
    return "(" + this.time + ", " + reactionsInvoked + ", " + eventQ + ")";
  }

  /**
   * This equals method does NOT compare tags, only compares reactionsInvoked, eventQ, and whether
   * future events are equally distant.
   */
  @Override
  public boolean equals(Object o) {
    if (o == null) return false;
    if (o instanceof StateSpaceNode) {
      StateSpaceNode node = (StateSpaceNode) o;
      if (this.reactionsInvoked.equals(node.reactionsInvoked)
          && this.eventQ.equals(node.eventQ)
          && equidistant(this, node)) return true;
    }
    return false;
  }

  /**
   * Generate hash for the node. This hash function is used for checking the uniqueness of nodes. It
   * is not meant to be used as a hashCode() because doing so interferes with node insertion in the
   * state space diagram.
   */
  public int hash() {
    // Initial value
    int result = 17;

    // Generate hash for the reactions invoked.
    result = 31 * result + reactionsInvoked.hashCode();

    // Generate hash for the triggers in the queued events.
    int eventsHash =
        this.eventQ.stream()
            .map(Event::getTrigger)
            .map(TriggerInstance::getFullName)
            .mapToInt(Object::hashCode)
            .reduce(1, (a, b) -> 31 * a + b);
    result = 31 * result + eventsHash;

    // Generate hash for the time differences.
    long timeDiffHash =
        this.eventQ.stream()
            .mapToLong(e -> e.tag.timestamp - this.tag.timestamp)
            .reduce(1, (a, b) -> 31 * a + b);
    result = 31 * result + (int) timeDiffHash;

    return result;
  }
}
