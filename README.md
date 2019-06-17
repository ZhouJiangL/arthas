## Purpose

    The forking intends to enhance some functions and provide customization of some features about Arthas.

### Background

    Arthas support lots of commands helping us to diagnose some methods imeplemented in our applications.   
    
    These functions usually satisfy many requirements when we want to know what stacks are after the methods   
    be executed, how much time spending, etc. But in some situations, it haven't satisfied. For example,   
    after we called a method, not only we want to acquire time cost, but also the corresponding stacks.  
    
    So we established the branch, and provided an new command named "slow-trace".


### Key features

###### 1. slow-trace

    Basing on command trace, the command slow-trace supplies all of options that the trace provides. 
    
    And by adding an option "-c" and a time value , once the time cost of a method calling exceeds   
    the value, slow-trace can print the time cost, which is as same as that of the command trace,   
    and the related current thread stack information. 
    
    Attention please, the time value is in milliseconds.
    
    
### Usage

###### 1. slow-trace

   $ slow-trace demo.MathGame run '#cost > 10' -c 10
    
