package org.lflang.target.property;

import java.util.List;
import org.lflang.Target;

public class ThreadingProperty extends AbstractBooleanProperty {

  @Override
  public List<Target> supportedTargets() {
    return List.of(Target.C, Target.CCPP, Target.Python);
  }

  @Override
  public Boolean initialValue() {
    return true;
  }
}
