## Purpose

    The forking intends to enhance some functions and provide customization of some features about Arthas.

### Background

    Arthas support lots of commands helping us to diagnose some methods imeplemented in our applications.   
    These functions usually satisfy many requirements when we want to know what stacks are after the methods be executed, how much time spending, etc. <br />
    But in some situations, it haven't satisfied. For example, after we called a method, not only we want to acquire time cost, but also the corresponding stacks.   
So we established the branch, and provided <br />an new command named "slow-trace"


### Key features

##### 1. slow-trace

    trace demo.MathGame run
