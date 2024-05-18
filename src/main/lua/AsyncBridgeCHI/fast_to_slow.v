

initial begin
    deq_clock = 0;
    deq_reset = 0;
end

always #14 deq_clock <= ~deq_clock;

