package org.usfirst.frc.team4145.shared.AutoStateMachine;

import edu.wpi.first.wpilibj.command.Command;
import jaci.pathfinder.Trajectory;
import org.usfirst.frc.team4145.commands.FollowPath;
import org.usfirst.frc.team4145.shared.AutoTrajectory.Path;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.util.Objects.requireNonNull;


/**
 * An extendable class that kicks out a Linked list of commandQueueGroups
 * this replaces the command group class
 */
public class QueueGroup {

    private Queue<CommandQueueGroup> queuedStates;
    private Trajectory m_RightDrive, m_LeftDrive;

    public QueueGroup() {
        queuedStates = new ConcurrentLinkedQueue<>();
    }

    protected void addDrive(List<Path.Waypoint> path, boolean isReversed){
        requireNonNull(path, "Command cannot be null");
        queuedStates.add(new CommandQueueGroup(new Command[]{new FollowPath(new Path(path), isReversed)}, 50, false));
    }

    protected void addSequential(Command command, long timeOutMs) {
        requireNonNull(command, "Command cannot be null");
        queuedStates.add(new CommandQueueGroup(new Command[]{command}, timeOutMs, true));
    }

    protected void addParallel(Command[] commands, long timeOutMs) {
        requireNonNull(commands, "Command cannot be null");
        queuedStates.add(new CommandQueueGroup(commands, timeOutMs, true));
    }

    public ConcurrentLinkedQueue<CommandQueueGroup> getQueuedStates() {
        return (ConcurrentLinkedQueue<CommandQueueGroup>) queuedStates;
    }
}
