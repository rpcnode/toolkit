import {Entity as Entity_, Column as Column_, PrimaryColumn as PrimaryColumn_, Index as Index_, IntColumn as IntColumn_, DateTimeColumn as DateTimeColumn_, StringColumn as StringColumn_, BigIntColumn as BigIntColumn_} from "@subsquid/typeorm-store"

@Index_("idx_transfer_from_block_number_28d4a37d", ["from", "blockNumber"], {unique: false})
@Index_("idx_transfer_to_block_number_12873b69", ["to", "blockNumber"], {unique: false})
@Entity_()
export class Transfer {
    constructor(props?: Partial<Transfer>) {
        Object.assign(this, props)
    }

    @PrimaryColumn_()
    id!: string

    @Index_("idx_transfer_block_number_16bfc223")
    @IntColumn_({nullable: false})
    blockNumber!: number

    @Index_("idx_transfer_timestamp_cc1edef1")
    @DateTimeColumn_({nullable: false})
    timestamp!: Date

    @Index_("idx_transfer_extrinsic_hash_d2a2fdff")
    @StringColumn_({nullable: true})
    extrinsicHash!: string | undefined | null

    @IntColumn_({nullable: true})
    extrinsicIndex!: number | undefined | null

    @IntColumn_({nullable: false})
    eventIndex!: number

    @StringColumn_({nullable: false})
    from!: string

    @StringColumn_({nullable: false})
    to!: string

    @BigIntColumn_({nullable: false})
    amount!: bigint
}
