package org.usfirst.frc.team4145;

import org.usfirst.frc.team4145.commands.FollowPath;
import org.usfirst.frc.team4145.shared.AutoStateMachine.QueueGroup;
import org.usfirst.frc.team4145.shared.AutoTrajectory.Path;
import org.usfirst.frc.team4145.shared.AutoTrajectory.Translation2d;

import java.util.ArrayList;
import java.util.List;

public class testAuto extends QueueGroup {

    public testAuto(int variant){
        List<Path.Waypoint> first_path = new ArrayList<>();
        first_path.add(new Path.Waypoint(new Translation2d(0, 0), 120.0));
        first_path.add(new Path.Waypoint(new Translation2d(24, 0), 72.0));
        addSequential(new FollowPath(new Path(first_path), false), 10000);
    }

}