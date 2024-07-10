local env = require "env"
local expect = env.expect

local dcache_core_0_a = ([[
    | valid
    | ready
    | opcode
    | address
    | source
]]):bundle {hier = cfg.top, prefix = "dcache_in_0_0_a_", name = "dcache_core_0_a"}

local dcache_core_1_a = ([[
    | valid
    | ready
    | opcode
    | address
    | source
]]):bundle {hier = cfg.top, prefix = "dcache_in_1_0_a_", name = "dcache_core_1_a"}

local l2 = dut.u_SimpleL2CacheWrapper.l2

verilua "appendTasks" {
    function ()
        sim.dump_wave()

        env.negedge(5)
            dcache_core_0_a.valid:set(1)
            dcache_core_0_a.bits.address:set(bit.lshift(0, 6), true)
        env.negedge()
            dcache_core_0_a.valid:set(0)

        env.negedge(5)
            dcache_core_0_a.valid:set(1)
            dcache_core_0_a.bits.address:set(bit.lshift(1, 6), true)
        env.negedge()
            dcache_core_0_a.valid:set(0)

        env.negedge(5)
            dcache_core_0_a.valid:set(1)
            dcache_core_0_a.bits.address:set(bit.lshift(2, 6), true)
        env.negedge()
            dcache_core_0_a.valid:set(0)

        
        env.negedge(5)
            dcache_core_1_a.valid:set(1)
            dcache_core_1_a.bits.address:set(bit.lshift(0, 6), true)
        env.negedge()
            dcache_core_1_a.valid:set(0)

        env.negedge(5)
            dcache_core_1_a.valid:set(1)
            dcache_core_1_a.bits.address:set(bit.lshift(1, 6), true)
        env.negedge()
            dcache_core_1_a.valid:set(0)

        
        env.negedge(5)
            l2.auto_sink_nodes_in_1_b_bits_source:set_force(16)
            l2.auto_sink_nodes_in_1_b_valid:set_force(1)
        env.negedge()
            l2.auto_sink_nodes_in_1_b_bits_source:set_release()
            l2.auto_sink_nodes_in_1_b_valid:set_release()

        env.negedge(5)
            l2.auto_sink_nodes_in_1_b_bits_source:set_force(48)
            l2.auto_sink_nodes_in_1_b_valid:set_force(1)
        env.negedge()
            l2.auto_sink_nodes_in_1_b_bits_source:set_release()
            l2.auto_sink_nodes_in_1_b_valid:set_release()


        env.negedge(5)
            dcache_core_0_a.valid:set(1)
            dcache_core_0_a.bits.address:set(bit.lshift(0, 6), true)
            dcache_core_0_a.bits.opcode:set(5)
            dcache_core_1_a.valid:set(1)
            dcache_core_1_a.bits.address:set(bit.lshift(1, 6), true)
            dcache_core_1_a.bits.opcode:set(2)
        env.negedge()
            dcache_core_0_a.valid:set(0)
            dcache_core_1_a.valid:set(0)
        

        env.posedge(100)
        env.TEST_SUCCESS()
    end
}