package org.usfirst.frc.team4145.subsystems.RobotDriveV4;

import com.ctre.phoenix.motorcontrol.ControlMode;
import com.ctre.phoenix.motorcontrol.FeedbackDevice;
import com.ctre.phoenix.motorcontrol.NeutralMode;
import edu.wpi.first.wpilibj.*;
import edu.wpi.first.wpilibj.command.Subsystem;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import org.usfirst.frc.team4145.Constants;
import org.usfirst.frc.team4145.Robot;
import org.usfirst.frc.team4145.RobotMap;
import org.usfirst.frc.team4145.shared.AutoTrajectory.AdaptivePurePursuitController;
import org.usfirst.frc.team4145.shared.AutoTrajectory.Kinematics;
import org.usfirst.frc.team4145.shared.AutoTrajectory.Path;
import org.usfirst.frc.team4145.shared.AutoTrajectory.RigidTransform2d;
import org.usfirst.frc.team4145.shared.MixedDrive;

import static org.usfirst.frc.team4145.subsystems.RobotDriveV4.RobotDriveV4.DriveControlState.OPEN_LOOP;


public class RobotDriveV4 extends Subsystem implements PIDOutput, PIDSource {

    public enum DriveControlState {
        OPEN_LOOP("Open Loop"), PATH_FOLLOWING_CONTROL("Path following");
        private String s;

        DriveControlState(String name){
            s = name;
        }

        @Override
        public String toString() {
            return s;
        }
    }

    //used internally for data
    private MixedDrive m_MixedDriveInstance;
    private Notifier m_NotifierInstance;
    private boolean isProfiling = false;
    private PIDController gyroLock;
    private double pidOutput = 0; //DO NOT MODIFY
    private boolean enLock = false;
    private boolean isReversed = false;
    private boolean isLowGear = false;
    private double[] operatorInput = {0, 0, 0}; //last input set from joystick update
    private double index = 0;
    private PIDSourceType type = PIDSourceType.kDisplacement;
    private DriveControlState driveControlState = OPEN_LOOP;
    private AdaptivePurePursuitController pathFollowingController;

    public RobotDriveV4() {
        m_MixedDriveInstance = new MixedDrive(RobotMap.driveFrontLeft, RobotMap.driveRearLeft, RobotMap.driveFrontRight, RobotMap.driveRearRight);
        m_NotifierInstance = new Notifier(periodic);
        reset();
        startPeriodic();
        initGyro();
    }

    public void startPeriodic(){
        m_NotifierInstance.startPeriodic(Constants.DRIVETRAIN_UPDATE_RATE);
    }

    private Runnable periodic = () -> {
        if(DriverStation.getInstance().isOperatorControl() && DriverStation.getInstance().isEnabled()) {
            operatorInput = getAdjStick();
            if (isReversed) {
                operatorInput[0] *= -1;
                operatorInput[1] *= -1;
            }
            isLowGear = Robot.oi.getMasterStick().getPOV() >= 0;
            if (isLowGear) {
                operatorInput[0] *= Constants.getTeleopYCutPercentage();
                operatorInput[1] *= Constants.getTeleopXCutPercentage();
            }
            if (enLock) operatorInput[2] = pidOutput;
            else setTarget(getGyro()); // Safety feature in case PID gets enabled
            if(Constants.ENABLE_MP_TEST_MODE) operatorInput = motionProfileTestMode();
            driveCartesian(operatorInput[1], -operatorInput[0], operatorInput[2]);
        }
        if(DriverStation.getInstance().isAutonomous() && DriverStation.getInstance().isEnabled()){
            if(driveControlState == DriveControlState.PATH_FOLLOWING_CONTROL){
                updatePathFollower();
                if (isFinishedPath()) {
                    stop();
                }
            }
            else{
                driveCartesian(operatorInput[1], -operatorInput[0], operatorInput[2]);
            }
        }
        smartDashboardUpdates();
    };

    public double getGyro() {
        return ((RobotMap.ahrs.getYaw() + 360) % 360); //add 360 to make all positive then mod by 360 to get remainder
    }

    public double getLeftEncoder(){
        return -RobotMap.driveFrontLeft.getSensorCollection().getQuadraturePosition();
    }

    public double getRightEncoder(){
        return RobotMap.driveFrontRight.getSensorCollection().getQuadraturePosition();
    }

    public void setOperatorInput(double[] input){
        operatorInput = input;
    }

    public void enableTo(double rot, boolean en) {
        this.setTarget(rot);
        this.enableLock(en);
    }

    public synchronized void followPath(Path path, boolean reversed) {
        if (driveControlState != DriveControlState.PATH_FOLLOWING_CONTROL) {
            configAuto();
        }
        pathFollowingController = new AdaptivePurePursuitController(Constants.PATH_FOLLOWING_LOOKAHEAD,
                Constants.PATH_FOLLOWING_MAX_ACCELERATION, Constants.DRIVETRAIN_UPDATE_RATE, path, reversed, 0.25);
        driveControlState = DriveControlState.PATH_FOLLOWING_CONTROL;
        updatePathFollower();
    }

    public void stop(){
        driveControlState = DriveControlState.OPEN_LOOP;
    }

    public synchronized boolean isFinishedPath() {
        return (driveControlState == DriveControlState.PATH_FOLLOWING_CONTROL && pathFollowingController.isDone())
                || driveControlState != DriveControlState.PATH_FOLLOWING_CONTROL;
    }

    private void updatePathFollower() {
        RigidTransform2d robot_pose = RobotMap.robotPose.getLatestFieldToVehicle().getValue();
        RigidTransform2d.Delta command = pathFollowingController.update(robot_pose, Timer.getFPGATimestamp());
        Kinematics.DriveVelocity setpoint = Kinematics.inverseKinematics(command);

        // Scale the command to respect the max velocity limits
        double max_vel = 0.0;
        max_vel = Math.max(max_vel, Math.abs(setpoint.left));
        max_vel = Math.max(max_vel, Math.abs(setpoint.right));
        if (max_vel > Constants.PATH_FOLLOWING_MAX_VELOCITY) {
            double scaling = Constants.PATH_FOLLOWING_MAX_VELOCITY / max_vel;
            setpoint = new Kinematics.DriveVelocity(setpoint.left * scaling, setpoint.right * scaling);
        }
        driveTank(inchesPerSecondToRpm(setpoint.left), inchesPerSecondToRpm(setpoint.right));
    }

    public double pidGet(){
        return getGyro();
    }
    public void pidWrite(double output){
        pidOutput = output;
    }

    public void setDynamicBrakeMode(boolean brakeFL, boolean brakeRL, boolean brakeFR, boolean brakeRR) {
        RobotMap.driveFrontLeft.setNeutralMode(brakeFL? NeutralMode.Brake : NeutralMode.Coast);
        RobotMap.driveRearLeft.setNeutralMode(brakeRL? NeutralMode.Brake : NeutralMode.Coast);
        RobotMap.driveFrontRight.setNeutralMode(brakeFR? NeutralMode.Brake : NeutralMode.Coast);
        RobotMap.driveRearRight.setNeutralMode(brakeRR? NeutralMode.Brake : NeutralMode.Coast);
    }

    public void configTeleop(){
        reset();
        RobotMap.driveFrontLeft.set(ControlMode.PercentOutput, 0);
        RobotMap.driveFrontRight.set(ControlMode.PercentOutput, 0);
        RobotMap.driveRearLeft.set(ControlMode.PercentOutput, 0);
        RobotMap.driveRearRight.set(ControlMode.PercentOutput, 0);
    }

    public void configAuto() {
        reset();
        RobotMap.driveFrontLeft.set(ControlMode.Velocity, 0);
        RobotMap.driveFrontLeft.configSelectedFeedbackSensor(FeedbackDevice.CTRE_MagEncoder_Relative, 0, 0);
        RobotMap.driveFrontLeft.selectProfileSlot(0, 0);
        RobotMap.driveFrontLeft.config_kF(0, Constants.getLeftKF(), 0);
        RobotMap.driveFrontLeft.config_kP(0, Constants.getLeftKP(), 0);
        RobotMap.driveFrontLeft.config_kI(0, Constants.getLeftKI(), 0);
        RobotMap.driveFrontLeft.config_kD(0, Constants.getLeftKD(), 0);
        RobotMap.driveFrontLeft.config_IntegralZone(0, 0, 0);

        RobotMap.driveFrontRight.set(ControlMode.Velocity, 0);
        RobotMap.driveFrontRight.configSelectedFeedbackSensor(FeedbackDevice.CTRE_MagEncoder_Relative, 0, 0);
        RobotMap.driveFrontRight.selectProfileSlot(0, 0);
        RobotMap.driveFrontRight.config_kF(0, Constants.getRightKF(), 0);
        RobotMap.driveFrontRight.config_kP(0, Constants.getRightKP(), 0);
        RobotMap.driveFrontRight.config_kI(0, Constants.getRightKI(), 0);
        RobotMap.driveFrontRight.config_kD(0, Constants.getRightKD(), 0);
        RobotMap.driveFrontRight.config_IntegralZone(0, 0, 0);

        RobotMap.driveRearLeft.set(ControlMode.Follower, RobotMap.driveFrontLeft.getBaseID());
        RobotMap.driveRearRight.set(ControlMode.Follower, RobotMap.driveFrontRight.getBaseID());
    }

    public void reset(){
        resetEncoders();
        resetGyro();
    }

    private void resetEncoders(){
        RobotMap.driveFrontLeft.setSelectedSensorPosition(0,0,0);
        RobotMap.driveRearLeft.setSelectedSensorPosition(0,0,0);
        RobotMap.driveFrontRight.setSelectedSensorPosition(0,0,0);
        RobotMap.driveRearRight.setSelectedSensorPosition(0,0,0);
    }

    private void resetGyro(){
        RobotMap.ahrs.reset();
    }

    private void smartDashboardUpdates() {
        SmartDashboard.putNumber("FPGA Time", Timer.getFPGATimestamp());
        SmartDashboard.putNumber("Gyro Angle", getGyro());
        SmartDashboard.putNumber("Left Motor Voltage", RobotMap.driveFrontLeft.getMotorOutputVoltage());
        SmartDashboard.putNumber("Right Motor Voltage", RobotMap.driveFrontRight.getMotorOutputVoltage());
        SmartDashboard.putNumber("Left Talon Voltage", RobotMap.driveRearLeft.getBusVoltage());
        SmartDashboard.putNumber("Right Talon Voltage", RobotMap.driveRearRight.getBusVoltage());
        SmartDashboard.putNumber("Right Wheel Encoder", getRightEncoder());
        SmartDashboard.putNumber("Left Wheel Encoder", getLeftEncoder());
        SmartDashboard.putNumber("Right Wheel Distance", getRightEncoder() * ((Constants.WHEEL_DIAMETER * Math.PI) / Constants.COUNTS_PER_REV));
        SmartDashboard.putNumber("Left Wheel Distance", getLeftEncoder() * ((Constants.WHEEL_DIAMETER * Math.PI) / Constants.COUNTS_PER_REV));
        SmartDashboard.putString("Drive Control Mode", driveControlState.toString());
    }

    private static double inchesToRotations(double inches) {
        return inches / (Constants.WHEEL_DIAMETER * Math.PI);
    }

    private static double inchesPerSecondToRpm(double inches_per_second) {
        return inchesToRotations(inches_per_second) * 60;
    }

    private void initGyro(){
        gyroLock = new PIDController(Constants.getGyrolockKp(), Constants.getGyrolockKi(), Constants.getGyrolockKd(), this, this);
        gyroLock.setAbsoluteTolerance(Constants.getGyrolockTol());
        gyroLock.setOutputRange(-Constants.getGyrolockLim(), Constants.getGyrolockLim());
        gyroLock.setInputRange(0, 360);
        gyroLock.setContinuous();
    }

    private double[] motionProfileTestMode(){
        double[] test = {0.0, 0.0, 0.0};
        test[0] = -0.000104 * index;
        if (DriverStation.getInstance().isOperatorControl() && DriverStation.getInstance().isEnabled()) index++;
        else index = 0;
        return test;
    }

    private void driveCartesian(double ySpeed, double xSpeed, double zRotation) {
        m_MixedDriveInstance.driveCartesian(ySpeed, xSpeed, zRotation);
    }

    private void driveTank(double leftSpeed, double rightSpeed) {
        m_MixedDriveInstance.tankDrive((leftSpeed * 512)/75 , (rightSpeed * 512)/75);
    }

    private void setTarget(double target) {
        gyroLock.setSetpoint(target);
    }

    private void enableLock(boolean en) {
        enLock = en;
        if (enLock) gyroLock.enable();
        else gyroLock.disable();
    }

    public void setLowGear(boolean toSet){
        isLowGear = toSet;
    }

    private double[] getAdjStick() {
        double[] out = new double[3];
        out[0] = evalDeadBand(Robot.oi.getMasterStick().getY(), Constants.getTeleopDeadband()) * Constants.getTeleopYPercentage();
        out[1] = evalDeadBand(Robot.oi.getMasterStick().getX(), Constants.getTeleopDeadband()) * Constants.getTeleopXPercentage();
        out[2] = evalDeadBand(Robot.oi.getMasterStick().getZ(), Constants.getTeleopDeadband()) * Constants.getTeleopZPercentage();;
        return out;
    }

    // figures out if the stick value is within the deadband
    private double evalDeadBand(double stickInpt, double deadBand) {
        if (Math.abs(stickInpt) < deadBand) {
            return 0;
        } else {
            if (stickInpt < 0) {
                return (0 - Math.pow(stickInpt, 2));
            } else {
                return Math.pow(stickInpt, 2);
            }
        }
    }

    @Override
    public void setPIDSourceType(PIDSourceType pidSource) {
        type = pidSource;
    }

    @Override
    public PIDSourceType getPIDSourceType() {
        return type;
    }

    protected void initDefaultCommand() {

    }

}