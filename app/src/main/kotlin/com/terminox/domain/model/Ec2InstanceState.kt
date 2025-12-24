package com.terminox.domain.model

/**
 * Lifecycle states for EC2 instances.
 */
enum class Ec2InstanceState {
    /** API call made, waiting for instance to reach running state */
    LAUNCHING,

    /** Instance is running, but SSH may not be ready yet */
    RUNNING,

    /** Instance running and SSH daemon accepting connections */
    SSH_READY,

    /** Termination initiated */
    STOPPING,

    /** Instance has been terminated */
    TERMINATED,

    /** Launch or operation failed */
    FAILED
}
