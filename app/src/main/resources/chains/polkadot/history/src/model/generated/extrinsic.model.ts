import {Entity as Entity_, Column as Column_, PrimaryColumn as PrimaryColumn_, Index as Index_, IntColumn as IntColumn_, StringColumn as StringColumn_, BooleanColumn as BooleanColumn_, BigIntColumn as BigIntColumn_, DateTimeColumn as DateTimeColumn_, JSONColumn as JSONColumn_} from "@subsquid/typeorm-store"

@Index_("idx_extrinsic_signer_block_number_fc616dec", ["signer", "blockNumber"], {unique: false})
@Index_("idx_extrinsic_pallet_call_block_number_3452bb14", ["pallet", "call", "blockNumber"], {unique: false})
@Entity_()
export class Extrinsic {
    constructor(props?: Partial<Extrinsic>) {
        Object.assign(this, props)
    }

    @PrimaryColumn_()
    id!: string

    @Index_("idx_extrinsic_block_number_d3414dc1")
    @IntColumn_({nullable: false})
    blockNumber!: number

    @IntColumn_({nullable: false})
    index!: number

    @Index_("idx_extrinsic_hash_fb7b0484")
    @StringColumn_({nullable: false})
    hash!: string

    @StringColumn_({nullable: false})
    pallet!: string

    @StringColumn_({nullable: false})
    call!: string

    @StringColumn_({nullable: true})
    signer!: string | undefined | null

    @BooleanColumn_({nullable: false})
    success!: boolean

    @BigIntColumn_({nullable: true})
    tip!: bigint | undefined | null

    @Index_("idx_extrinsic_timestamp_5e15c657")
    @DateTimeColumn_({nullable: false})
    timestamp!: Date

    @JSONColumn_({nullable: true})
    args!: unknown | undefined | null
}
